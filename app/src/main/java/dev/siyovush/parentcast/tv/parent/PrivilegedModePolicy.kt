package dev.siyovush.parentcast.tv.parent

import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode

/** Admission state for the process-local first trial; it never arms a future boot. */
data class PrivilegedModeState(val enabled: Boolean = false, val exitPending: Boolean = false)

data class PrivilegedModeRequirements(
    val isSystemApp: Boolean,
    val pinConfigured: Boolean,
    val recoveryAvailable: Boolean,
    val isParentCastHome: Boolean,
    val isForeground: Boolean,
)

enum class PrivilegedModeRefusal {
    DISABLED, EXIT_PENDING, NOT_SYSTEM_APP, PIN_NOT_CONFIGURED, RECOVERY_UNAVAILABLE,
    NOT_PARENTCAST_HOME, NOT_FOREGROUND, ANOTHER_LOCK_TASK_ACTIVE, NOT_TASK_ROOT, LOCK_NOT_CONFIRMED,
}

/** Pure admission policy, not evidence of privileges or a replacement for actual Android mode checks. */
object PrivilegedModePolicy {
    /** Called only by normal MainActivity in foreground; null permits one explicit candidate launch. */
    fun launchRefusal(
        state: PrivilegedModeState,
        requirements: PrivilegedModeRequirements,
        currentMode: ManagedLockTaskMode,
    ): PrivilegedModeRefusal? = stateRefusal(state, requirements)
        ?: if (currentMode != ManagedLockTaskMode.NONE) PrivilegedModeRefusal.ANOTHER_LOCK_TASK_ACTIVE else null

    /** Called on candidate resume. Any refusal requires disarming and finish(), never pinning fallback. */
    fun candidateRefusal(
        state: PrivilegedModeState,
        requirements: PrivilegedModeRequirements,
        actualMode: ManagedLockTaskMode,
        isTaskRoot: Boolean,
    ): PrivilegedModeRefusal? = stateRefusal(state, requirements) ?: when {
        !isTaskRoot -> PrivilegedModeRefusal.NOT_TASK_ROOT
        actualMode != ManagedLockTaskMode.LOCKED -> PrivilegedModeRefusal.LOCK_NOT_CONFIRMED
        else -> null
    }

    /** A new authenticated action is the only way to create an enabled admission state. */
    fun enableAfterPin(
        parentAuthorized: Boolean,
        requirements: PrivilegedModeRequirements,
        currentMode: ManagedLockTaskMode,
    ): PrivilegedModeState? {
        if (!parentAuthorized) return null
        val enabled = PrivilegedModeState(enabled = true)
        return enabled.takeIf { launchRefusal(it, requirements, currentMode) == null }
    }

    /** An exit state cannot be reused for admission by a lifecycle callback. */
    fun exitAfterPin(parentAuthorized: Boolean): PrivilegedModeState? =
        if (parentAuthorized) refusedState() else null

    /** Safety refusal needs no PIN: an unsupported candidate must never trap the user in a loop. */
    fun refusedState(): PrivilegedModeState = PrivilegedModeState(enabled = false, exitPending = true)

    private fun stateRefusal(state: PrivilegedModeState, requirements: PrivilegedModeRequirements): PrivilegedModeRefusal? = when {
        state.exitPending -> PrivilegedModeRefusal.EXIT_PENDING
        !state.enabled -> PrivilegedModeRefusal.DISABLED
        !requirements.isSystemApp -> PrivilegedModeRefusal.NOT_SYSTEM_APP
        !requirements.pinConfigured -> PrivilegedModeRefusal.PIN_NOT_CONFIGURED
        !requirements.recoveryAvailable -> PrivilegedModeRefusal.RECOVERY_UNAVAILABLE
        !requirements.isParentCastHome -> PrivilegedModeRefusal.NOT_PARENTCAST_HOME
        !requirements.isForeground -> PrivilegedModeRefusal.NOT_FOREGROUND
        else -> null
    }
}
