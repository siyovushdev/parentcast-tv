package dev.siyovush.parentcast.tv.parent

import android.os.SystemClock
import dev.siyovush.parentcast.tv.web.PinSecurity
import dev.siyovush.parentcast.tv.web.PinSettings

/** No PIN or PIN hash is stored here: the existing M1 PinSettings remains the source of truth. */
data class ParentAttemptState(
    val failures: Int = 0,
    val windowStartMs: Long = 0,
    val lastObservedMs: Long = 0,
) {
    init {
        require(failures in 0..5 && windowStartMs >= 0 && lastObservedMs >= windowStartMs)
    }
}

interface ParentAttemptStore {
    fun read(): ParentAttemptState
    fun write(state: ParentAttemptState)
}

enum class ParentAccessResult { GRANTED, DENIED, THROTTLED, NO_PIN, STORAGE_ERROR, CANCELLED }

/** authenticate performs PBKDF2 and disk writes: invoke it off the Android main thread. */
class ParentAccessGate(
    private val pinSettings: PinSettings,
    private val attemptStore: ParentAttemptStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionClock: () -> Long = SystemClock::elapsedRealtime,
    private val verifier: (String, String) -> Boolean = PinSecurity::verifyPin,
    private val sessionTimeoutMs: Long = 120_000,
) {
    private val authenticationLock = Any()
    private val sessionLock = Any()
    private var generation = 0L
    private var sessionActive = false
    private var sessionTouchedMs = 0L

    init {
        require(sessionTimeoutMs > 0)
    }

    fun authenticate(pin: String): ParentAccessResult = synchronized(authenticationLock) {
        val requestGeneration = synchronized(sessionLock) {
            sessionActive = false
            ++generation
        }
        val record = try {
            pinSettings.pinRecord()?.takeIf { pinSettings.hasPin() && it.isNotBlank() }
        } catch (_: Exception) {
            return@synchronized ParentAccessResult.STORAGE_ERROR
        } ?: return@synchronized ParentAccessResult.NO_PIN
        val state: ParentAttemptState
        val now: Long
        try {
            val stored = attemptStore.read()
            // A wall-clock rollback cannot erase a persisted lockout after app restart or reboot.
            now = maxOf(clock().coerceAtLeast(0), stored.lastObservedMs)
            state = normalized(stored, now)
            if (state.failures >= MAX_FAILURES) return@synchronized ParentAccessResult.THROTTLED
            // Reserve the attempt before verification, so killing the process cannot skip accounting.
            attemptStore.write(ParentAttemptState(state.failures + 1,
                if (state.failures == 0) now else state.windowStartMs, now))
        } catch (_: Exception) {
            return@synchronized ParentAccessResult.STORAGE_ERROR
        }
        val valid = try {
            PinSecurity.isValidPin(pin) && verifier(pin, record)
        } catch (_: Exception) {
            false
        }
        if (!valid) return@synchronized ParentAccessResult.DENIED
        try {
            attemptStore.write(ParentAttemptState(lastObservedMs = now))
        } catch (_: Exception) {
            return@synchronized ParentAccessResult.STORAGE_ERROR
        }
        synchronized(sessionLock) {
            if (generation != requestGeneration) return@synchronized ParentAccessResult.CANCELLED
            sessionTouchedMs = sessionClock()
            sessionActive = true
            ParentAccessResult.GRANTED
        }
    }

    /** Also invalidates any in-flight PBKDF2 result, without waiting for it. */
    fun lock() = synchronized(sessionLock) {
        generation++
        sessionActive = false
    }

    fun authorized(): Boolean = synchronized(sessionLock) { sessionIsValid(sessionClock()) }

    /** Only an already-authorized session can be extended by parent interaction. */
    fun touch(): Boolean = synchronized(sessionLock) {
        val now = sessionClock()
        if (!sessionIsValid(now)) return@synchronized false
        sessionTouchedMs = now
        true
    }

    fun retryAfterMs(): Long = try {
        val stored = attemptStore.read()
        val now = maxOf(clock().coerceAtLeast(0), stored.lastObservedMs)
        val state = normalized(stored, now)
        if (state.failures < MAX_FAILURES) 0 else (WINDOW_MS - (now - state.windowStartMs)).coerceIn(1, WINDOW_MS)
    } catch (_: Exception) {
        WINDOW_MS
    }

    private fun normalized(state: ParentAttemptState, now: Long): ParentAttemptState =
        if (state.failures > 0 && now - state.windowStartMs >= WINDOW_MS) ParentAttemptState(lastObservedMs = now) else state

    // Caller holds only sessionLock; this never waits for slow PIN verification or storage.
    private fun sessionIsValid(now: Long): Boolean {
        if (!sessionActive || now < sessionTouchedMs || now - sessionTouchedMs >= sessionTimeoutMs) {
            sessionActive = false
            return false
        }
        return true
    }

    companion object {
        const val MAX_FAILURES = 5
        const val WINDOW_MS = 600_000L
    }
}
