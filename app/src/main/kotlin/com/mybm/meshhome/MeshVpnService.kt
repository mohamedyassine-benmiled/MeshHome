package com.mybm.meshhome

import android.app.*
import android.content.*
import android.location.LocationManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.OsConstants
import com.mybm.meshhome.core.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Callback-driven VPN controller. No periodic policy/status loop and no idle wake alarms. */
class MeshVpnService : VpnService() {
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "meshhome-control") }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val stopping = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)
    private val statusQueued = AtomicBoolean(false)
    private val inputEpoch = AtomicLong()
    private val mapDirty = AtomicBoolean(true)
    private val packageDirty = AtomicBoolean(true)
    private val credentialsRevision = AtomicLong()
    @Volatile private var pump: PacketPump? = null
    @Volatile private var sessionEpoch = 0L
    @Volatile private var started = false
    @Volatile private var terminalError: String? = null
    private var nativeHandle = 0L
    private var configuration: SessionConfiguration? = null
    private var appliedMap: String? = null
    private var cachedMap: MeshMap? = null
    private var installedApps = emptySet<String>()
    private var requestedApps = emptySet<String>()
    private var lastInputs: String? = null
    private var targetIds = emptySet<String>()
    private var statusWarnings = ""
    private var refreshFailed = false
    private var lastRefresh = 0L
    private var retryAt = 0L
    private var mapRetryAt = 0L
    private var retryTask: ScheduledFuture<*>? = null
    private var mapTask: ScheduledFuture<*>? = null
    private val reconnectBackoff = RetryBackoff()
    private val mapBackoff = RetryBackoff(30_000, 15 * 60_000)
    private lateinit var settings: SettingsStore
    private lateinit var accounts: AccountRepository
    private lateinit var monitor: Networks.Monitor
    private lateinit var power: PowerManager
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        packageDirty.set(true); inputEpoch.incrementAndGet(); requestReconcile()
    }
    private val accountListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.endsWith("ciphertext")) {
            mapDirty.set(true); credentialsRevision.incrementAndGet(); requestReconcile()
        }
    }
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action?.startsWith("android.intent.action.PACKAGE_") == true) packageDirty.set(true)
            inputEpoch.incrementAndGet(); requestReconcile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this); accounts = AccountRepository(this)
        power = getSystemService(PowerManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Meshnet connection", NotificationManager.IMPORTANCE_LOW))
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        getSharedPreferences("credential_vault", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(accountListener)
        monitor = Networks.Monitor(this) { invalidate ->
            inputEpoch.incrementAndGet()
            // A lost/replaced default network stops packet processing immediately,
            // even if the control worker is finishing a bound HTTPS request.
            if (invalidate) pump?.halt()
            requestReconcile()
        }
        monitor.start()
        registerEvents(IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        })
        registerEvents(IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    // All filters contain protected system broadcasts; older Android has no
    // exported-state flag. The API 33+ branch always sets NOT_EXPORTED explicitly.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerEvents(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(events, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || (intent?.action == RECHECK && !started)) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        if (stopping.get()) return START_NOT_STICKY
        if (!started) {
            startForeground(1, notification("Starting Meshnet…")); started = true; ConnectionState.running = true
        }
        if (intent?.action == RECHECK) { packageDirty.set(true); inputEpoch.incrementAndGet() }
        requestReconcile()
        return START_NOT_STICKY
    }

    private fun submit(action: () -> Unit) {
        if (stopping.get()) return
        try { worker.execute { if (!stopping.get()) action() } } catch (_: RejectedExecutionException) { }
    }

    /** Collapse a burst of onAvailable/capabilities/link/settings events into one evaluation. */
    private fun requestReconcile() {
        if (!started || stopping.get() || !queued.compareAndSet(false, true)) return
        try { worker.schedule({ queued.set(false); if (!stopping.get()) reconcile() }, 150, TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) { queued.set(false) }
    }

    private fun reconcile() {
        try {
            val observedEpoch = inputEpoch.get()
            val current = monitor.snapshot(readSsid = settings.homeSsids.isNotEmpty())
            val apps = settings.apps
            if (packageDirty.getAndSet(false) || apps != requestedApps) {
                requestedApps = apps
                installedApps = apps.filter { it != packageName && runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }.toSet()
            }
            if (mapDirty.getAndSet(false)) cachedMap = accounts.cachedMap()
            val rules = settings.rules
            val inputs = listOf(current, settings.mode, settings.homeSsids.sorted(), apps.sorted(), rules,
                settings.upstreamDns, credentialsRevision.get(), installedApps.sorted()).joinToString("|")
            if (inputs != lastInputs) {
                lastInputs = inputs; retryAt = 0; reconnectBackoff.reset(); retryTask?.cancel(false); retryTask = null
            }
            val paused = when (NetworkPolicy.evaluate(settings.mode, settings.homeSsids, current?.snapshot, apps, installedApps)) {
                PolicyDecision.HomeBypass -> "Home Wi-Fi: Meshnet paused; using normal LAN DNS/routing."
                PolicyDecision.NoNetwork -> "Paused: waiting for an available direct network."
                PolicyDecision.WifiRequired -> "Paused: waiting for Wi-Fi."
                PolicyDecision.NoSelectedApps -> "Paused: select at least one installed app."
                PolicyDecision.Eligible -> null
            }
            if (paused != null) { pause(paused); return }
            checkNotNull(current)
            val invalid = when {
                current.captivePortal -> "Paused: sign in to this Wi-Fi network's captive portal."
                current.strictPrivateDns -> "Not connected: set strict Android Private DNS to off or automatic for local overrides."
                !NativeTelio.available -> "Not connected: matching native Meshnet engine missing for this CPU."
                !accounts.hasToken -> "Not connected: sign in with a Nord token first."
                rules.none { it.enabled } -> "Paused: add and enable at least one domain-to-device rule."
                else -> null
            }
            if (invalid != null) { pause(invalid); return }
            val now = SystemClock.elapsedRealtime()
            if (retryAt > now) { scheduleRetry(); return }
            if (power.isDeviceIdleMode && (nativeHandle == 0L || pump?.isRunning != true || configuration?.network != current.bindingKey)) {
                pause("Paused: waiting for Android to leave Doze before reconnecting."); return
            }
            val interval = PowerPolicy.mapRefreshInterval(power.isInteractive, power.isPowerSaveMode)
            var map = cachedMap
            val due = if (mapRetryAt > 0) mapRetryAt else if (lastRefresh == 0L) 0L else lastRefresh + interval
            if (!power.isDeviceIdleMode && (map == null || now >= due)) {
                try {
                    map = accounts.refresh(current.network); cachedMap = map
                    lastRefresh = SystemClock.elapsedRealtime(); mapRetryAt = 0; refreshFailed = false; mapBackoff.reset()
                } catch (e: NordApiException) {
                    if (e.status in setOf(401, 403, 404)) { terminalError = "Not connected: ${safeMessage(e)}"; shutdown(); stopSelf(); return }
                    if (map == null) throw e
                    refreshFailed = true; mapRetryAt = SystemClock.elapsedRealtime() + mapBackoff.nextDelay()
                } catch (e: Exception) {
                    if (map == null) throw e
                    refreshFailed = true; mapRetryAt = SystemClock.elapsedRealtime() + mapBackoff.nextDelay()
                }
            }
            // A network/settings event during HTTP invalidates this work. Never
            // establish a tunnel on the snapshot that preceded that event.
            if (stopping.get() || observedEpoch != inputEpoch.get()) { requestReconcile(); return }
            checkNotNull(map)
            val dnsPeers = if (map.local.ipv6.isEmpty()) map.peers.map { it.copy(ipv6 = emptyList()) } else map.peers
            val targets = rules.filter { it.enabled }.mapNotNull { rule -> dnsPeers.find { it.id == rule.deviceId } }.distinctBy { it.id }
            val addresses = targets.flatMap { it.ipv4 + it.ipv6 }.distinct()
            if (addresses.isEmpty()) { pause("Paused: selected domain targets have no usable Meshnet addresses."); return }
            if (map.local.ipv4.isEmpty() || DnsPackets.ADDRESS in addresses || DnsPackets.ADDRESS in map.local.ipv4) {
                pause("Not connected: missing phone Meshnet IPv4 address or virtual DNS address conflict."); return
            }
            val nextConfiguration = SessionConfiguration.create(current.bindingKey, current.dns, settings.upstreamDns,
                installedApps, map.local.ipv4 + map.local.ipv6, rules, dnsPeers)
            val nativeMap = map.forTargets(targets.map { it.id }.toSet())
            if (power.isDeviceIdleMode && configuration != nextConfiguration) {
                pause("Paused: configuration changed; waiting to leave Doze before reconnecting."); return
            }
            if (configuration != nextConfiguration || pump?.isRunning != true) {
                disconnect()
                connect(current, map, nativeMap, dnsPeers, addresses, rules)
                configuration = nextConfiguration; appliedMap = nativeMap
            } else if (appliedMap != nativeMap) {
                NativeTelio.updateMap(nativeHandle, nativeMap); appliedMap = nativeMap
            }
            targetIds = targets.map { it.id }.toSet()
            val missing = rules.any { rule -> rule.enabled && dnsPeers.none { it.id == rule.deviceId && (it.ipv4.isNotEmpty() || it.ipv6.isNotEmpty()) } }
            statusWarnings = (if (missing) " Some rule targets are missing or have no usable addresses." else "") +
                (if (current.snapshot.transport == Transport.WIFI && current.snapshot.ssid == null && settings.homeSsids.isNotEmpty()) " Wi-Fi name unavailable; home bypass not applied." else "")
            retryAt = 0; reconnectBackoff.reset(); retryTask?.cancel(false); retryTask = null
            refreshStatus()
            scheduleMapRefresh()
        } catch (e: Exception) { connectionFailed(safeMessage(e)) }
        catch (_: UnsatisfiedLinkError) { pause("Not connected: incompatible native engine. Install the current APK.") }
    }

    private fun connect(current: CurrentNetwork, map: MeshMap, nativeMap: String, dnsPeers: List<MeshDevice>, addresses: List<String>, rules: List<DnsRule>) {
        check(prepare(this) == null) { "VPN permission revoked. Open MeshHome and connect again." }
        val builder = Builder().setSession("MeshHome").setMtu(1280).setBlocking(false).setUnderlyingNetworks(arrayOf(current.network))
            .addDnsServer(DnsPackets.ADDRESS).addRoute(DnsPackets.ADDRESS, 32)
        (map.local.ipv4 + map.local.ipv6).forEach { builder.addAddress(it, if (':' in it) 128 else 32) }
        addresses.forEach { builder.addRoute(it, if (':' in it) 128 else 32) }
        builder.allowFamily(OsConstants.AF_INET); builder.allowFamily(OsConstants.AF_INET6)
        check(installedApps.isNotEmpty()); installedApps.forEach(builder::addAllowedApplication)
        val tun = checkNotNull(builder.establish()) { "Android refused the VPN interface." }
        val epoch = ++sessionEpoch
        val resolver = settings.upstreamDns.takeIf { it.isNotBlank() }?.let(::listOf) ?: current.dns
        val packets = try { PacketPump(tun, rules, dnsPeers, DnsUpstream(this, current.network, resolver)::query,
            map.local.ipv4 + map.local.ipv6, addresses) {
            submit { if (sessionEpoch == epoch) connectionFailed("Packet processing failed.") }
        } } catch (e: Exception) { tun.close(); throw e }
        pump = packets
        val protector = object : NativeTelio.SocketProtector {
            override fun protectAndBind(fd: Int): Boolean = try {
                check(protect(fd)); ParcelFileDescriptor.fromFd(fd).use { current.network.bindSocket(it.fileDescriptor) }; true
            } catch (_: Exception) { false }
        }
        val listener = object : NativeTelio.Events { override fun changed() { requestStatus(epoch) } }
        val privateKey = accounts.privateKey()
        val fd = packets.nativeFd()
        nativeHandle = try { NativeTelio.start(privateKey, nativeMap, fd, protector, listener) }
            catch (e: UnsatisfiedLinkError) { ParcelFileDescriptor.adoptFd(fd).close(); throw e }
        check(nativeHandle != 0L) { "Native Meshnet engine could not start." }
        if (stopping.get()) { disconnect(); return }
        packets.start()
    }

    private fun requestStatus(epoch: Long) {
        if (!statusQueued.compareAndSet(false, true)) return
        submit {
            statusQueued.set(false)
            if (sessionEpoch == epoch && nativeHandle != 0L) {
                try { refreshStatus() } catch (e: Exception) { connectionFailed(safeMessage(e)) }
            }
        }
    }

    private fun refreshStatus() {
        if (nativeHandle == 0L) return
        if (pump?.isRunning != true) {
            publish("Not connected: packet processing stopped; rechecking connection.")
            requestReconcile()
            return
        }
        val nodes = org.json.JSONArray(NativeTelio.status(nativeHandle))
        val connected = (0 until nodes.length()).count {
            val node = nodes.getJSONObject(it)
            node.optString("state") == "connected" && node.optString("identifier") in targetIds
        }
        publish("Meshnet running · $connected/${targetIds.size} target peers connected${if (refreshFailed) " · map refresh failed; using cached peers" else ""}$statusWarnings")
    }

    private fun scheduleMapRefresh() {
        mapTask?.cancel(false); mapTask = null
        if (nativeHandle == 0L || stopping.get() || power.isDeviceIdleMode) return
        val due = if (mapRetryAt > 0) mapRetryAt else lastRefresh + PowerPolicy.mapRefreshInterval(power.isInteractive, power.isPowerSaveMode)
        mapTask = worker.schedule({ mapTask = null; requestReconcile() }, (due - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    private fun connectionFailed(message: String) {
        if (message.startsWith("Native Meshnet initialization failed:")) android.util.Log.w("MeshHomeInit", message)
        disconnect(); retryAt = SystemClock.elapsedRealtime() + reconnectBackoff.nextDelay()
        publish("Not connected: $message Automatic retry is backed off."); scheduleRetry()
    }
    private fun scheduleRetry() {
        retryTask?.cancel(false); retryTask = null
        if (stopping.get() || power.isDeviceIdleMode) return
        retryTask = worker.schedule({ retryTask = null; requestReconcile() }, (retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
    private fun pause(message: String) {
        disconnect(); retryTask?.cancel(false); retryTask = null; publish(message)
    }
    private fun disconnect() {
        ++sessionEpoch; configuration = null; appliedMap = null
        mapTask?.cancel(false); mapTask = null
        pump?.close(); pump = null
        if (nativeHandle != 0L) { runCatching { NativeTelio.stop(nativeHandle) }; nativeHandle = 0L }
    }

    private fun publish(message: String) {
        if (stopping.get()) return
        if (ConnectionState.message == message) return
        ConnectionState.message = message
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MeshVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_meshhome).setContentTitle("MeshHome")
            .setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
    }

    override fun onRevoke() { shutdown(); stopSelf() }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        writer.println("MeshHome: ${ConnectionState.message}")
        writer.println("running=${ConnectionState.running} started=$started stopping=${stopping.get()}")
        writer.println("Selected apps: ${settings.apps.sorted().joinToString()}")
        writer.println("Enabled DNS rules: ${settings.rules.filter { it.enabled }.joinToString { it.pattern.value }}")
        writer.println("Packet pump: ${pump?.diagnostics() ?: "absent"}")
        writer.println("Controller: active=${worker.activeCount} queued=${worker.queue.size} reconcileQueued=${queued.get()}")
        writer.println("Network events: ${monitor.diagnostics()}")
        Thread.getAllStackTraces().filterKeys { it.name == "meshhome-control" }.forEach { (_, stack) ->
            stack.forEach { writer.println("  at $it") }
        }
    }
    /** Android may keep a VpnService bound after stopService/stopSelf. Closing
     * the data path must be explicit, not deferred until onDestroy is delivered.
     */
    private fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        ConnectionState.running = false; inputEpoch.incrementAndGet(); pump?.halt()
        runCatching { monitor.stop() }; runCatching { unregisterReceiver(events) }
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener)
        getSharedPreferences("credential_vault", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(accountListener)
        retryTask?.cancel(false); mapTask?.cancel(false)
        worker.execute { disconnect() }; worker.shutdown()
        ConnectionState.message = terminalError ?: "Disconnected"
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.mybm.meshhome.STOP"
        const val RECHECK = "com.mybm.meshhome.RECHECK"
        private const val CHANNEL = "meshnet"
        fun safeMessage(e: Exception): String = when (e) {
            is NordApiException -> e.message.orEmpty()
            is java.io.IOException -> "Network request failed. Check connectivity, DNS and Nord availability."
            is org.json.JSONException -> "Nord returned an unexpected response. Check API compatibility."
            is IllegalStateException, is IllegalArgumentException -> e.message ?: "Invalid configuration."
            else -> "Operation failed. Check permissions, credentials and device settings."
        }
    }
}
