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
    fun currentPinAuthorizesWithoutOpeningAnyWindow() {
        val manager = manager()
        val first = manager.completePairing("739251", "First", "client-a")!!
        val second = manager.completePairing("739251", "Second", "client-b")!!

        assertTrue(first.first.deviceId != second.first.deviceId)
        assertTrue(first.second != second.second)
    }

    @Test
    fun noConfiguredPinCannotIssueToken() {
        val store = FakeTokenStore()
        val manager = PairingManager(InMemoryPinSettings(), store) { 1_000 }

        assertNull(manager.completePairing("739251", "Test", "client"))
        assertEquals(0, store.createCount.get())
    }

    @Test
    fun invalidPinsNeverIssueToken() {
        val store = FakeTokenStore()
        val manager = manager(store)
        listOf("", "123", "abcdef", "000000").forEach {
            assertNull(manager.completePairing(it, "Test", "client"))
        }
        assertEquals(0, store.createCount.get())
    }

    @Test
    fun fiveFailuresBlockCorrectPinFromThatClient() {
        val store = FakeTokenStore()
        val manager = manager(store)
        repeat(5) { assertNull(manager.completePairing("000000", "Test", "client")) }

        assertNull(manager.completePairing("739251", "Test", "client"))
        assertEquals(0, store.createCount.get())
        assertNotNull(manager.completePairing("739251", "Other", "other-client"))
    }

    @Test
    fun clientCanSignInAfterFailureWindowExpires() {
        var now = 1_000L
        val manager = PairingManager(InMemoryPinSettings(), FakeTokenStore()) { now }
        manager.createPin("739251")
        repeat(5) { manager.completePairing("000000", "Test", "client") }
        now += 599_999
        assertNull(manager.completePairing("739251", "Test", "client"))
        now += 2
        assertNotNull(manager.completePairing("739251", "Test", "client"))
    }

    @Test
    fun successfulPinClearsEarlierFailures() {
        val manager = manager()
        repeat(2) {
            repeat(4) { assertNull(manager.completePairing("000000", "Test", "client")) }
            assertNotNull(manager.completePairing("739251", "Test", "client"))
        }
    }

    @Test
    fun pinChangeStillRequiresCurrentPin() {
        val manager = manager()
        assertFalse(manager.createPin("111111"))
        assertFalse(manager.createPin("111111", "000000"))
        assertNotNull(manager.completePairing("739251", "Test", "client"))
        assertTrue(manager.createPin("111111", "739251"))
        assertNull(manager.completePairing("739251", "Test", "client"))
        assertNotNull(manager.completePairing("111111", "Test", "client"))
    }

    @Test
    fun parallelValidLoginsIssueIndependentTokens() {
        val store = FakeTokenStore()
        val manager = manager(store)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) { index ->
                pool.submit<Pair<DeviceRecord, String>?> {
                    barrier.await()
                    manager.completePairing("739251", "Test", "client-$index")
                }
            }
            val responses = calls.map { it.get(30, TimeUnit.SECONDS)!! }
            assertEquals(2, responses.map { it.second }.distinct().size)
            assertEquals(2, store.createCount.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun parallelPinFailuresDoNotLoseAttempts() {
        val store = FakeTokenStore()
        val manager = manager(store)
        val barrier = CyclicBarrier(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = List(8) {
                pool.submit<Pair<DeviceRecord, String>?> {
                    barrier.await()
                    manager.completePairing("000000", "Test", "same-client")
                }
            }
            assertTrue(calls.map { it.get(30, TimeUnit.SECONDS) }.all { it == null })
            assertNull(manager.completePairing("739251", "Test", "same-client"))
            assertEquals(0, store.createCount.get())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun manager(store: FakeTokenStore = FakeTokenStore()) =
        PairingManager(InMemoryPinSettings(), store) { 1_000 }.also { it.createPin("739251") }

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
        val id = createCount.incrementAndGet()
        return DeviceRecord("device-$id", label, 1_000) to "dummy-token-$id"
    }

    override fun verify(token: String): DeviceRecord? = null
    override fun revoke(deviceId: String): Boolean = true
    override fun list(): List<DeviceRecord> = emptyList()
}
