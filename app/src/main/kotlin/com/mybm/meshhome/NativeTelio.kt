package com.mybm.meshhome

/** Matched to native/src/lib.rs. No reflection or guessed UniFFI signatures. */
object NativeTelio {
    val available: Boolean = try { System.loadLibrary("meshhome"); apiVersion() == 3 } catch (_: UnsatisfiedLinkError) { false }
    interface SocketProtector { fun protectAndBind(fd: Int): Boolean }
    interface Events { fun changed() }
    external fun apiVersion(): Int
    external fun start(privateKey: String, map: String, tunFd: Int, protector: SocketProtector, events: Events): Long
    external fun updateMap(handle: Long, map: String)
    external fun status(handle: Long): String
    external fun stop(handle: Long)
}
