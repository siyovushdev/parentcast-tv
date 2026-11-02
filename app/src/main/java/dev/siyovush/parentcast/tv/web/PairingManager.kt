package dev.siyovush.parentcast.tv.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PairingManager(
    private val pinSettings: PinSettings,
    private val tokenStore: TokenStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val attempts = PinAttemptLimiter()

    // A UI read must not wait for the crypto/write monitor. SharedPreferences reads are thread-safe.
    fun hasPin(): Boolean = pinSettings.hasPin()

    suspend fun changePin(pin: String, currentPin: String?): Boolean =
        withContext(Dispatchers.Default) { createPin(pin, currentPin) }

    @Synchronized
    fun createPin(pin: String, currentPin: String? = null): Boolean {
        if (!PinSecurity.isValidPin(pin)) return false
        if (hasPin() && !isCurrentPin(currentPin)) return false
        pinSettings.savePinRecord(PinSecurity.hashPin(pin))
        return true
    }

    @Synchronized
    fun completePairing(pin: String, label: String, clientKey: String): Pair<DeviceRecord, String>? {
        if (!attempts.isAllowed(clientKey, clock()) || !hasPin()) return null
        if (!isCurrentPin(pin)) {
            attempts.recordFailure(clientKey, clock())
            return null
        }
        attempts.clear(clientKey)
        return tokenStore.create(label)
    }

    private fun isCurrentPin(pin: String?): Boolean =
        pin != null && PinSecurity.isValidPin(pin) &&
            PinSecurity.verifyPin(pin, pinSettings.pinRecord().orEmpty())

    private data class PinAttemptLimiter(
        val maxFailures: Int = 5,
        val windowMs: Long = 600_000L,
    ) {
        private val failures = mutableMapOf<String, MutableList<Long>>()

        fun isAllowed(key: String, now: Long): Boolean {
            val recent = recentFailures(key, now)
            return recent.size < maxFailures
        }

        fun recordFailure(key: String, now: Long) {
            failures[key] = recentFailures(key, now).toMutableList().apply { add(now) }
        }

        fun clear(key: String) {
            failures.remove(key)
        }

        private fun recentFailures(key: String, now: Long): List<Long> =
            failures[key].orEmpty().filter { now - it <= windowMs }
    }
}
