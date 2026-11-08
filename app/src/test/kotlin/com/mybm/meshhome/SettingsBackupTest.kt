package com.mybm.meshhome

import com.mybm.meshhome.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val backup = SettingsBackup(NetworkMode.WIFI_ONLY, setOf("org.example.app"), setOf("Home"), "",
        listOf(DnsRule(DomainPattern.parse("photos.example.org"), "peer-1", true)))

    @Test fun settingsRoundTrip() {
        assertEquals(backup, SettingsBackup.parse(backup.exportText()))
    }

    @Test fun tokenIsImportOnly() {
        val imported = SettingsBackup.parse(JSONObject(backup.exportText()).put("nordToken", "synthetic-test-token").toString())
        assertEquals("synthetic-test-token", imported.nordToken)
        assertFalse(imported.exportText().contains("nordToken"))
        assertFalse(imported.exportText().contains("synthetic-test-token"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectBadDns() {
        SettingsBackup.parse(JSONObject(backup.exportText()).put("upstreamDns", "not-an-ip").toString())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectUnknownVersion() {
        SettingsBackup.parse(JSONObject(backup.exportText()).put("version", 99).toString())
    }
}
