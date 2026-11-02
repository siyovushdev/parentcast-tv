package dev.siyovush.parentcast.tv.parent

import dev.siyovush.parentcast.tv.kiosk.ManagedLockTaskMode

/** Process-local, one-use permission for a parent-requested trial; never persisted or put in intents. */
class PrivilegedTrialSession(private val clock: () -> Long) {
    private data class Entry(val state: PrivilegedModeState, val issuedAtMs: Long)
    private var entry: Entry? = null
    private var recoveryIssuedAtMs: Long? = null

    @Synchronized
    fun authorizeEntry(
        parentAuthorized: Boolean,
        requirements: PrivilegedModeRequirements,
        currentMode: ManagedLockTaskMode,
    ): Boolean {
        entry = null
        val now = clock()
        expireRecovery(now)
        if (now < 0 || recoveryIssuedAtMs != null) return false
        val state = PrivilegedModePolicy.enableAfterPin(parentAuthorized, requirements, currentMode) ?: return false
        entry = Entry(state, now)
        return true
    }

    @Synchronized
    fun consumeEntry(): PrivilegedModeState? {
        val permission = entry ?: return null
        entry = null
        return permission.state.takeIf { valid(permission.issuedAtMs, clock()) }
    }

    @Synchronized
    fun requestRecoveryAfterPin(parentAuthorized: Boolean): Boolean {
        if (!parentAuthorized) return false
        val now = clock()
        if (now < 0) return false
        entry = null
        recoveryIssuedAtMs = now
        return true
    }

    @Synchronized
    fun hasPendingRecovery(): Boolean {
        expireRecovery(clock())
        return recoveryIssuedAtMs != null
    }

    /** Do not spend the permission while finish() has not actually returned Android to NONE. */
    @Synchronized
    fun consumeRecovery(actualMode: ManagedLockTaskMode, foreground: Boolean): Boolean {
        expireRecovery(clock())
        if (recoveryIssuedAtMs == null || actualMode != ManagedLockTaskMode.NONE || !foreground) return false
        recoveryIssuedAtMs = null
        return true
    }

    /** Cancel an entry attempt without discarding a parent's pending safe exit. */
    @Synchronized
    fun clearEntry() {
        entry = null
    }

    private fun expireRecovery(now: Long) {
        recoveryIssuedAtMs?.let { if (!valid(it, now)) recoveryIssuedAtMs = null }
    }

    private fun valid(issuedAtMs: Long, now: Long): Boolean =
        now >= issuedAtMs && now - issuedAtMs < PERMISSION_TTL_MS

    companion object {
        const val PERMISSION_TTL_MS = 30_000L
    }
}
