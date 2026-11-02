package dev.siyovush.parentcast.tv.parent

import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrivilegedTrialSessionTest {
    private val ready = PrivilegedModeRequirements(true, true, true, true, true)
    private var now = 1000L
    private fun newSession() = PrivilegedTrialSession { now }

    @Test fun entryRequiresParentAndAllExistingPolicyChecks() {
        val session = newSession()
        assertFalse(session.authorizeEntry(false, ready, ManagedLockTaskMode.NONE))
        assertNull(session.consumeEntry())
        assertFalse(session.authorizeEntry(true, ready.copy(pinConfigured = false), ManagedLockTaskMode.NONE))
        assertFalse(session.authorizeEntry(true, ready.copy(recoveryAvailable = false), ManagedLockTaskMode.NONE))
        assertFalse(session.authorizeEntry(true, ready.copy(isSystemApp = false), ManagedLockTaskMode.NONE))
        assertFalse(session.authorizeEntry(true, ready.copy(isForeground = false), ManagedLockTaskMode.NONE))
        assertFalse(session.authorizeEntry(true, ready, ManagedLockTaskMode.PINNED))
        assertFalse(session.authorizeEntry(true, ready, ManagedLockTaskMode.LOCKED))
        assertNull(session.consumeEntry())
    }

    @Test fun entryIsOneUseAndUnauthenticatedReplacementCannotLeaveAnOlderGrant() {
        val session = newSession()
        assertTrue(session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE))
        assertEquals(PrivilegedModeState(enabled = true), session.consumeEntry())
        assertNull(session.consumeEntry())
        assertTrue(session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE))
        assertFalse(session.authorizeEntry(false, ready, ManagedLockTaskMode.NONE))
        assertNull(session.consumeEntry())
    }

    @Test fun entryExpiresAtThirtySecondsAndClockRollbackFailsClosed() {
        val session = newSession()
        session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE)
        now += 30_000
        assertNull(session.consumeEntry())
        session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE)
        now--
        assertNull(session.consumeEntry())
    }

    @Test fun recoveryWaitsForActualNoneAndForegroundThenCanBeConsumedOnlyOnce() {
        val session = newSession()
        assertFalse(session.requestRecoveryAfterPin(false))
        assertFalse(session.hasPendingRecovery())
        assertTrue(session.requestRecoveryAfterPin(true))
        assertFalse(session.consumeRecovery(ManagedLockTaskMode.LOCKED, true))
        assertTrue(session.hasPendingRecovery())
        assertFalse(session.consumeRecovery(ManagedLockTaskMode.PINNED, true))
        assertFalse(session.consumeRecovery(ManagedLockTaskMode.NONE, false))
        assertTrue(session.hasPendingRecovery())
        assertTrue(session.consumeRecovery(ManagedLockTaskMode.NONE, true))
        assertFalse(session.hasPendingRecovery())
        assertFalse(session.consumeRecovery(ManagedLockTaskMode.NONE, true))
    }

    @Test fun recoveryExpiresAndSurvivesNeitherClockRollbackNorProcessRecreation() {
        val session = newSession()
        session.requestRecoveryAfterPin(true)
        now += 30_000
        assertFalse(session.hasPendingRecovery())
        assertFalse(session.consumeRecovery(ManagedLockTaskMode.NONE, true))
        session.requestRecoveryAfterPin(true)
        now--
        assertFalse(session.hasPendingRecovery())
        session.requestRecoveryAfterPin(true)
        val recreated = newSession()
        assertFalse(recreated.hasPendingRecovery())
        assertFalse(recreated.consumeRecovery(ManagedLockTaskMode.NONE, true))
        assertNull(recreated.consumeEntry())
    }

    @Test fun recoveryCancelsEntryAndPreventsRelockWhileExitIsPending() {
        val session = newSession()
        session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE)
        session.requestRecoveryAfterPin(true)
        assertNull(session.consumeEntry())
        assertFalse(session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE))
        assertTrue(session.hasPendingRecovery())
        assertTrue(session.consumeRecovery(ManagedLockTaskMode.NONE, true))
        assertTrue(session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE))
    }

    @Test fun clearingEntryDoesNotDiscardRecoveryAndNewProcessHasNoEntryPermission() {
        val session = newSession()
        session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE)
        assertNull(newSession().consumeEntry())
        session.clearEntry()
        assertNull(session.consumeEntry())
        session.requestRecoveryAfterPin(true)
        session.clearEntry()
        assertTrue(session.hasPendingRecovery())
        assertTrue(session.consumeRecovery(ManagedLockTaskMode.NONE, true))
    }

    @Test fun simultaneousConsumersCanSpendEachPermissionOnlyOnce() {
        val session = newSession()
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        try {
            session.authorizeEntry(true, ready, ManagedLockTaskMode.NONE)
            val entries = List(8) { pool.submit<PrivilegedModeState?> {
                barrier.await(5, TimeUnit.SECONDS)
                session.consumeEntry()
            } }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, entries.count { it != null })
            session.requestRecoveryAfterPin(true)
            val recoveries = List(8) { pool.submit<Boolean> {
                barrier.await(5, TimeUnit.SECONDS)
                session.consumeRecovery(ManagedLockTaskMode.NONE, true)
            } }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, recoveries.count { it })
        } finally {
            pool.shutdownNow()
        }
    }
}
