package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PairingManagerTest {
    @Test
    fun pairingCodeWorksOnce() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!

        assertNotNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }

    @Test
    fun expiredChallengeIsRejected() {
        var now = 1_000L
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { now }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!

        now += 120_001
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }

    @Test
    fun blankPinIsRejectedWithoutIssuingToken() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!

        assertNull(manager.completePairing(challenge.code, "", "Test", "client"))
    }

    @Test
    fun repeatedPinFailuresAreLimited() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!

        repeat(5) {
            assertNull(manager.completePairing(challenge.code, "000000", "Test", "client"))
        }
        assertNull(manager.completePairing(challenge.code, "739251", "Test", "client"))
    }

    @Test
    fun pinChangeRequiresCurrentPin() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")

        assertFalse(manager.createPin("111111"))
        assertFalse(manager.createPin("111111", "000000"))
        assertNotNull(manager.beginPairing("739251"))
        assertTrue(manager.createPin("111111", "739251"))
        assertNull(manager.beginPairing("739251"))
        assertNotNull(manager.beginPairing("111111"))
    }

    @Test
    fun pairingWindowRequiresCurrentPin() {
        val settings = InMemoryPinSettings()
        val manager = PairingManager(settings, FakeTokenStore()) { 1_000 }
        manager.createPin("739251")

        assertNull(manager.beginPairing("000000"))
        assertNull(manager.beginPairing(""))
        assertNotNull(manager.beginPairing("739251"))
    }

    @Test
    fun parallelRequestsConsumePairingCodeOnlyOnce() {
        val settings = InMemoryPinSettings()
        val store = FakeTokenStore()
        val manager = PairingManager(settings, store) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) { index ->
                pool.submit<Pair<DeviceRecord, String>?> {
                    barrier.await()
                    manager.completePairing(challenge.code, "739251", "Test", "client-$index")
                }
            }
            val responses = calls.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, responses.count { it != null })
            assertEquals(1, store.createCount.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun parallelPinFailuresDoNotLoseAttempts() {
        val settings = InMemoryPinSettings()
        val store = FakeTokenStore()
        val manager = PairingManager(settings, store) { 1_000 }
        manager.createPin("739251")
        val challenge = manager.beginPairing("739251")!!
        val barrier = CyclicBarrier(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = List(8) {
                pool.submit<Pair<DeviceRecord, String>?> {
                    barrier.await()
                    manager.completePairing(challenge.code, "000000", "Test", "same-client")
                }
            }
            val responses = calls.map { it.get(30, TimeUnit.SECONDS) }

            assertTrue(responses.all { it == null })
            assertNull(manager.completePairing(challenge.code, "739251", "Test", "same-client"))
            assertEquals(0, store.createCount.get())
        } finally {
            pool.shutdownNow()
        }
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
    val createCount = AtomicInteger()

    override fun create(label: String): Pair<DeviceRecord, String> {
        createCount.incrementAndGet()
        return DeviceRecord("device-1", label, 1_000) to "dummy-token"
    }

    override fun verify(token: String): DeviceRecord? = null
    override fun revoke(deviceId: String): Boolean = true
    override fun list(): List<DeviceRecord> = emptyList()
}
