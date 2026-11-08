package com.mybm.meshhome

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.mybm.meshhome.core.*
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One blocking poll multiplexes TUN, native packets and a shutdown pipe. No idle polling. */
class PacketPump(
    private val tun: ParcelFileDescriptor,
    rules: List<DnsRule>, devices: List<MeshDevice>, private val upstream: (ByteArray) -> ByteArray,
    localAddresses: List<String>, targetAddresses: List<String>,
    private val onFailure: () -> Unit,
) : Closeable {
    private val local = FileDescriptor()
    private val native = FileDescriptor()
    private val stopped = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    private val dns = DnsMessages(rules, devices)
    private val firewall = ClientFirewall(localAddresses, targetAddresses)
    private val tcp = DnsTcp(::answer)
    private val wake = ParcelFileDescriptor.createPipe()
    private val pool = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128), ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val timers = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val timerLock = Any()
    private var timer: ScheduledFuture<*>? = null
    private var deadline: Long? = null
    @Volatile private var reader: Thread? = null
    private var nativeClosed = false
    @Volatile private var stopReason = "none"
    val isRunning: Boolean get() = !stopped.get() && reader?.isAlive == true
    private val tunPackets = AtomicLong()
    private val nativePackets = AtomicLong()
    private val nativeAllowed = AtomicLong()
    private val outboundAllowed = AtomicLong()
    private val dnsRequests = AtomicLong()
    private val dnsResponses = AtomicLong()
    private val dnsFailures = AtomicLong()
    fun diagnostics(): String = "stopped=${stopped.get()} reason=$stopReason tun=${tunPackets.get()} native=${nativePackets.get()} " +
        "meshOut=${outboundAllowed.get()} meshIn=${nativeAllowed.get()} dnsRequests=${dnsRequests.get()} " +
        "dnsResponses=${dnsResponses.get()} dnsFailures=${dnsFailures.get()}"

    init {
        try { Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK, 0, local, native) }
        catch (e: Exception) { wake.forEach { runCatching { it.close() } }; throw e }
    }

    /** Rust adopts this duplicate even when initialization fails. */
    fun nativeFd(): Int = ParcelFileDescriptor.dup(native).detachFd()

    @Synchronized fun start() {
        check(!stopped.get() && reader == null) { "Packet pump already stopped or started." }
        Os.close(native); nativeClosed = true
        reader = Thread(::readPackets, "meshhome-packets").also { it.start() }
    }

    private fun readPackets() {
        val descriptors = arrayOf(tun.fileDescriptor, local, wake[0].fileDescriptor).map { descriptor ->
            StructPollfd().apply { fd = descriptor; events = OsConstants.POLLIN.toShort() }
        }.toTypedArray()
        val buffer = ByteArray(65535)
        try {
            while (!stopped.get()) {
                try { Os.poll(descriptors, -1) }
                catch (e: ErrnoException) { if (e.errno == OsConstants.EINTR) continue else throw e }
                if (stopped.get()) break
                for (index in 0..1) {
                    val descriptor = descriptors[index]
                    check(descriptor.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) == 0)
                    if (descriptor.revents.toInt() and OsConstants.POLLIN == 0) continue
                    val count = try { Os.read(descriptor.fd, buffer, 0, buffer.size) }
                        catch (e: ErrnoException) { if (e.errno == OsConstants.EAGAIN) continue else throw e }
                    check(count > 0)
                    val packet = buffer.copyOf(count)
                    if (index == 1) {
                        nativePackets.incrementAndGet()
                        if (firewall.incoming(packet, SystemClock.elapsedRealtime())) { nativeAllowed.incrementAndGet(); inject(packet) }
                    } else { tunPackets.incrementAndGet(); outgoing(packet) }
                }
            }
        } catch (e: Exception) {
            stopReason = if (e is ErrnoException) "I/O errno ${e.errno}" else e.javaClass.simpleName
            if (!stopped.getAndSet(true)) onFailure()
        } finally { cleanup() }
    }

    private fun outgoing(packet: ByteArray) {
        if (!DnsPackets.isDnsDestination(packet)) {
            if (firewall.outgoing(packet, SystemClock.elapsedRealtime())) { outboundAllowed.incrementAndGet(); writePacket(local, packet) }
            return
        }
        val request = DnsPackets.decode(packet) ?: return
        dnsRequests.incrementAndGet()
        pool.execute {
            if (stopped.get()) return@execute
            runCatching {
                if (request.protocol == 17) {
                    val query = request.data.copyOfRange(8, request.data.size)
                    val response = answer(query)
                    if (response.isNotEmpty()) inject(DnsPackets.udpReply(request, DnsMessages.truncateForUdp(response, DnsMessages.udpLimit(query))))
                } else {
                    tcp.accept(request, SystemClock.elapsedRealtime()).forEach(::inject)
                    scheduleDnsDeadline()
                }
            }
        }
    }

    private fun answer(query: ByteArray): ByteArray = try {
        dns.answer(query, upstream).also {
            dnsResponses.incrementAndGet()
            if (it.size >= 4 && it[3].toInt() and 15 != 0) dnsFailures.incrementAndGet()
        }
    } catch (_: Exception) { dnsFailures.incrementAndGet(); DnsMessages.failure(query) ?: byteArrayOf() }

    @Synchronized private fun inject(packet: ByteArray) {
        if (!stopped.get() && packet.isNotEmpty()) writePacket(tun.fileDescriptor, packet)
    }

    private fun writePacket(fd: FileDescriptor, packet: ByteArray) {
        try { Os.write(fd, packet, 0, packet.size) }
        catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN) throw e }
    }

    private fun scheduleDnsDeadline(): Unit = synchronized(timerLock) {
        if (stopped.get()) return@synchronized
        val next = tcp.nextDeadline()
        if (next == deadline) return@synchronized
        timer?.cancel(false); timer = null; deadline = next
        if (next != null) timer = timers.schedule({
            val relevant = synchronized(timerLock) {
                if (deadline != next) false else { deadline = null; timer = null; true }
            }
            if (relevant && !stopped.get()) pool.execute {
                if (!stopped.get()) {
                    runCatching { tcp.tick(SystemClock.elapsedRealtime()).forEach(::inject) }
                    scheduleDnsDeadline()
                }
            }
        }, (next - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    /** Wakes the reader immediately, without timer polling or closing an fd underneath poll. */
    @Synchronized fun halt() {
        if (!stopped.get()) stopReason = "controller halt"
        if (!stopped.getAndSet(true)) runCatching { Os.write(wake[1].fileDescriptor, byteArrayOf(1), 0, 1) }
        pool.shutdownNow(); timers.shutdownNow()
        if (reader == null) cleanup()
    }

    override fun close() {
        halt()
        val thread = reader
        if (thread == null) cleanup() else if (thread !== Thread.currentThread()) thread.join(1500)
        // A reader that is still unwinding retains descriptor ownership and cleans up in finally.
    }

    @Synchronized private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        stopped.set(true); pool.shutdownNow(); timers.shutdownNow()
        runCatching { Os.close(local) }
        if (!nativeClosed) { runCatching { Os.close(native) }; nativeClosed = true }
        wake.forEach { runCatching { it.close() } }
        tun.close()
    }
}
