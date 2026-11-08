package com.mybm.meshhome

/** In-memory truth: a killed process must never restore a persisted "connected" label. */
object ConnectionState {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    @Volatile var running: Boolean = false
    @Volatile var message: String = "Disconnected"
        set(value) {
            if (field == value) return
            field = value
            listeners.forEach { runCatching { it(value) } }
        }
    fun observe(listener: (String) -> Unit) { listeners.add(listener); listener(message) }
    fun removeObserver(listener: (String) -> Unit) { listeners.remove(listener) }
}
