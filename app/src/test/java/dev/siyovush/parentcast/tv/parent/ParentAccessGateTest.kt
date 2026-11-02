package dev.siyovush.parentcast.tv.parent

import dev.siyovush.parentcast.tv.web.PinSecurity
import dev.siyovush.parentcast.tv.web.PinSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ParentAccessGateTest {
    private class Store : ParentAttemptStore {
        var state = ParentAttemptState()
        var failRead = false
        var failWrite = false
        override fun read(): ParentAttemptState {
            check(!failRead)
            return state
        }
        override fun write(state: ParentAttemptState) {
            check(!failWrite)
            this.state = state
        }
    }
    private class Settings(var record: String? = "test record") : PinSettings {
        var writes = 0
        override fun hasPin() = !record.isNullOrBlank()
        override fun pinRecord() = record
        override fun savePinRecord(record: String) { writes++; this.record = record }
    }
    private class Fixture {
        val settings = Settings()
        val store = Store()
        var wall = 10_000L
        var monotonic = 0L
        var verifies = 0
        fun gate() = ParentAccessGate(settings, store, { wall }, { monotonic }, { pin, _ -> verifies++; pin == "739251" })
    }

    @Test fun grantsOnlyExistingPinAndDoesNotModifyItsRecord() {
        val f = Fixture(); val gate = f.gate()
        assertFalse(gate.authorized())
        assertEquals(ParentAccessResult.DENIED, gate.authenticate("000000"))
        assertFalse(gate.authorized())
        assertEquals(ParentAccessResult.GRANTED, gate.authenticate("739251"))
        assertTrue(gate.authorized())
        assertEquals(0, f.store.state.failures)
        assertEquals("test record", f.settings.record)
        assertEquals(0, f.settings.writes)
    }

    @Test fun missingPinNeverGrantsAccessAndMalformedInputCountsWithoutPbkdf() {
        val f = Fixture(); val gate = f.gate()
        f.settings.record = null
        assertEquals(ParentAccessResult.NO_PIN, gate.authenticate("739251"))
        assertFalse(gate.authorized())
        f.settings.record = "test record"
        assertEquals(ParentAccessResult.DENIED, gate.authenticate("x"))
        assertEquals(1, f.store.state.failures)
        assertEquals(0, f.verifies)
    }

    @Test fun rateLimitSurvivesNewGateAndAllowsRetryAfterWindow() {
        val f = Fixture(); val gate = f.gate()
        repeat(5) { assertEquals(ParentAccessResult.DENIED, gate.authenticate("000000")) }
        val restarted = f.gate()
        assertEquals(ParentAccessResult.THROTTLED, restarted.authenticate("739251"))
        assertEquals(5, f.verifies)
        assertEquals(600_000L, restarted.retryAfterMs())
        f.wall += 600_000
        assertEquals(0L, restarted.retryAfterMs())
        assertEquals(ParentAccessResult.GRANTED, restarted.authenticate("739251"))
    }

    @Test fun wallClockRollbackDoesNotRemovePersistedRateLimit() {
        val f = Fixture(); val gate = f.gate()
        repeat(5) { gate.authenticate("000000"); f.wall += 100 }
        f.wall = 0
        assertTrue(f.gate().retryAfterMs() > 0)
        assertEquals(ParentAccessResult.THROTTLED, f.gate().authenticate("739251"))
    }

    @Test fun concurrentAttemptsCannotExceedTheVerificationLimit() {
        val f = Fixture(); val gate = f.gate()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = List(8) { pool.submit<ParentAccessResult> { gate.authenticate("000000") } }
                .map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(5, results.count { it == ParentAccessResult.DENIED })
            assertEquals(3, results.count { it == ParentAccessResult.THROTTLED })
            assertEquals(5, f.verifies)
            assertEquals(5, f.store.state.failures)
            assertFalse(gate.authorized())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun expiryLockAndTouchDoNotReopenSession() {
        val f = Fixture(); val gate = f.gate()
        assertEquals(ParentAccessResult.GRANTED, gate.authenticate("739251"))
        f.monotonic = 100_000
        assertTrue(gate.touch())
        f.monotonic = 219_999
        assertTrue(gate.authorized())
        f.monotonic = 220_000
        assertFalse(gate.authorized())
        assertFalse(gate.touch())
        gate.authenticate("739251")
        gate.lock()
        assertFalse(gate.authorized())
        assertFalse(f.gate().authorized())
    }

    @Test fun storageFailureNeverGrantsOrLeavesPreviousSessionOpen() {
        val f = Fixture(); val gate = f.gate()
        gate.authenticate("739251")
        f.store.failRead = true
        assertEquals(ParentAccessResult.STORAGE_ERROR, gate.authenticate("739251"))
        assertFalse(gate.authorized())
        f.store.failRead = false; f.store.failWrite = true
        assertEquals(ParentAccessResult.STORAGE_ERROR, gate.authenticate("739251"))
        assertFalse(gate.authorized())
    }

    @Test fun backgroundLockInvalidatesSlowVerificationWithoutWaiting() {
        val settings = Settings(); val store = Store()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val gate = ParentAccessGate(settings, store, sessionClock = { 0L }, verifier = { _, _ ->
            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); true
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val verification = pool.submit<ParentAccessResult> { gate.authenticate("739251") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gate.lock()
            assertFalse(gate.authorized())
            finish.countDown()
            assertEquals(ParentAccessResult.CANCELLED, verification.get(5, TimeUnit.SECONDS))
            assertFalse(gate.authorized())
        } finally {
            finish.countDown(); pool.shutdownNow()
        }
    }

    @Test fun defaultVerifierUsesTheExistingM1PinHash() {
        val record = PinSecurity.hashPin("739251")
        val settings = Settings(record)
        val gate = ParentAccessGate(settings, Store(), sessionClock = { 0L })
        assertEquals(ParentAccessResult.DENIED, gate.authenticate("000000"))
        assertEquals(ParentAccessResult.GRANTED, gate.authenticate("739251"))
        assertTrue(gate.authorized())
        assertEquals(record, settings.record)
        assertEquals(0, settings.writes)
    }
}
