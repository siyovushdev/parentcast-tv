package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingManagerTest {
    @Test
    fun pairingCodeWorksOnce() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing()

        assertNotNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }

    @Test
    fun expiredChallengeIsRejected() {
        var now = 1_000L
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { now }
        manager.createPin("739251")
        val challenge = manager.beginPairing()

        now += 120_001
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }

    @Test
    fun blankPinIsRejectedWithoutIssuingToken() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing()

        assertNull(manager.completePairing(challenge.code, "", "Test", "client"))
    }

    @Test
    fun repeatedPinFailuresAreLimited() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing()

        repeat(5) {
            assertNull(manager.completePairing(challenge.code, "000000", "Test", "client"))
        }
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }
}

private class InMemoryPinSettings : PinSettings {
    private var record: String? = null
    override fun hasPin(): Boolean = !record.isNullOrBlank()
    override fun pinRecord(): String? = record
    override fun savePinRecord(record: String) {
        this.record = record
    }
}

private class FakeTokenStore : TokenStore {
    override fun create(label: String): Pair<DeviceRecord, String> =
        DeviceRecord("device-1", label, 1_000) to "dummy-token"

    override fun verify(token: String): DeviceRecord? = null
    override fun revoke(deviceId: String): Boolean = true
    override fun list(): List<DeviceRecord> = emptyList()
}
