package com.mybm.meshhome

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {
    @Test fun statusIsPushedOnlyWhenChangedAndObserversCanUnsubscribe() {
        ConnectionState.message = "Disconnected"
        val received = mutableListOf<String>()
        val listener: (String) -> Unit = { received.add(it) }
        ConnectionState.observe(listener)
        try {
            ConnectionState.message = "Running"
            ConnectionState.message = "Running"
            assertEquals(listOf("Disconnected", "Running"), received)
        } finally { ConnectionState.removeObserver(listener) }
        ConnectionState.message = "Disconnected"
        assertEquals(2, received.size)
    }
    @Test fun brokenObserverCannotCrashTheVpnController() {
        val observer: (String) -> Unit = { if (it == "Test error") error("Observer failure") }
        ConnectionState.observe(observer)
        try { ConnectionState.message = "Test error" }
        finally { ConnectionState.removeObserver(observer); ConnectionState.message = "Disconnected" }
    }
}
