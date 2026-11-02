package dev.siyovush.parentcast.tv.parent

import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode
import org.junit.Assert.*
import org.junit.Test

class PrivilegedModePolicyTest {
    private val policy = PrivilegedModePolicy
    private val ready = PrivilegedModeRequirements(true, true, true, true, true)
    private val enabled = PrivilegedModeState(enabled = true)

    @Test fun normalInstallAndDefaultPreferencesNeverLaunchPrivilegedCandidate() {
        assertEquals(PrivilegedModeRefusal.DISABLED,
            policy.launchRefusal(PrivilegedModeState(), ready, ManagedLockTaskMode.NONE))
        assertEquals(PrivilegedModeRefusal.NOT_SYSTEM_APP,
            policy.launchRefusal(enabled, ready.copy(isSystemApp = false), ManagedLockTaskMode.NONE))
        assertNull(policy.enableAfterPin(true, ready.copy(isSystemApp = false), ManagedLockTaskMode.NONE))
    }

    @Test fun onlyAnExplicitAuthenticatedEnableCanArmEntryOrClearExitMarker() {
        assertNull(policy.enableAfterPin(false, ready, ManagedLockTaskMode.NONE))
        assertEquals(enabled, policy.enableAfterPin(true, ready, ManagedLockTaskMode.NONE))
        assertNull(policy.launchRefusal(enabled, ready, ManagedLockTaskMode.NONE))
        val exited = policy.exitAfterPin(true)!!
        assertEquals(PrivilegedModeState(false, true), exited)
        assertEquals(PrivilegedModeRefusal.EXIT_PENDING,
            policy.launchRefusal(exited, ready, ManagedLockTaskMode.NONE))
        assertEquals(enabled, policy.enableAfterPin(true, ready, ManagedLockTaskMode.NONE))
    }

    @Test fun missingPinRecoveryHomeOrForegroundBlocksEntryEvenWhenEnabledAndSystem() {
        val failures = listOf(
            ready.copy(pinConfigured = false) to PrivilegedModeRefusal.PIN_NOT_CONFIGURED,
            ready.copy(recoveryAvailable = false) to PrivilegedModeRefusal.RECOVERY_UNAVAILABLE,
            ready.copy(isParentCastHome = false) to PrivilegedModeRefusal.NOT_PARENTCAST_HOME,
            ready.copy(isForeground = false) to PrivilegedModeRefusal.NOT_FOREGROUND,
        )
        failures.forEach { (requirements, reason) ->
            assertEquals(reason, policy.launchRefusal(enabled, requirements, ManagedLockTaskMode.NONE))
            assertNull(policy.enableAfterPin(true, requirements, ManagedLockTaskMode.NONE))
            assertEquals(reason, policy.candidateRefusal(enabled, requirements, ManagedLockTaskMode.LOCKED, true))
        }
    }

    @Test fun existingPinningOrAnotherLockedTaskNeverGetsNestedCandidate() {
        listOf(ManagedLockTaskMode.PINNED, ManagedLockTaskMode.LOCKED).forEach { mode ->
            assertEquals(PrivilegedModeRefusal.ANOTHER_LOCK_TASK_ACTIVE,
                policy.launchRefusal(enabled, ready, mode))
            assertNull(policy.enableAfterPin(true, ready, mode))
        }
    }

    @Test fun systemFlagOrPinningDoesNotProveActualManagedLock() {
        assertEquals(PrivilegedModeRefusal.LOCK_NOT_CONFIRMED,
            policy.candidateRefusal(enabled, ready, ManagedLockTaskMode.NONE, true))
        assertEquals(PrivilegedModeRefusal.LOCK_NOT_CONFIRMED,
            policy.candidateRefusal(enabled, ready, ManagedLockTaskMode.PINNED, true))
        assertEquals(PrivilegedModeRefusal.NOT_TASK_ROOT,
            policy.candidateRefusal(enabled, ready, ManagedLockTaskMode.LOCKED, false))
        assertNull(policy.candidateRefusal(enabled, ready, ManagedLockTaskMode.LOCKED, true))
    }

    @Test fun persistedExitOrSafetyRefusalSurvivesStartupAndEvenInconsistentEnabledFlag() {
        assertNull(policy.exitAfterPin(false))
        val stopped = policy.exitAfterPin(true)!!
        val restored = stopped.copy()
        assertEquals(PrivilegedModeRefusal.EXIT_PENDING,
            policy.launchRefusal(restored, ready, ManagedLockTaskMode.NONE))
        assertEquals(PrivilegedModeRefusal.EXIT_PENDING,
            policy.candidateRefusal(restored, ready, ManagedLockTaskMode.LOCKED, true))
        assertEquals(PrivilegedModeRefusal.EXIT_PENDING,
            policy.launchRefusal(PrivilegedModeState(true, true), ready, ManagedLockTaskMode.NONE))
        assertEquals(stopped, policy.refusedState())
    }
}
