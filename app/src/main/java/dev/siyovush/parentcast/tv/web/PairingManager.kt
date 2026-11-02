package dev.siyovush.parentcast.tv.web

import java.security.SecureRandom

class PairingManager(
    private val pinSettings: PinSettings,
    private val tokenStore: TokenStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val random = SecureRandom()
    private var challenge: PairingChallenge? = null
    private val attempts = PinAttemptLimiter()

    @Synchronized
    fun hasPin(): Boolean = pinSettings.hasPin()

    @Synchronized
    fun createPin(pin: String, currentPin: String? = null): Boolean {
        if (!PinSecurity.isValidPin(pin)) return false
        if (hasPin() && !isCurrentPin(currentPin)) return false
        pinSettings.savePinRecord(PinSecurity.hashPin(pin))
        return true
    }

    @Synchronized
    fun beginPairing(currentPin: String): PairingChallenge? {
        if (!hasPin() || !isCurrentPin(currentPin)) return null
        val now = clock()
        val code = (random.nextInt(900_000) + 100_000).toString()
        return PairingChallenge(code, now + PAIRING_WINDOW_MS).also { challenge = it }
    }

    @Synchronized
    fun completePairing(code: String, pin: String, label: String, clientKey: String): Pair<DeviceRecord, String>? {
        if (!attempts.isAllowed(clientKey, clock()) || !hasPin()) return null
        val current = challenge
        if (current == null || current.code != code || current.expiresAtMs < clock()) return null
        if (!isCurrentPin(pin)) {
            attempts.recordFailure(clientKey, clock())
            return null
        }
        attempts.clear(clientKey)
        challenge = null
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

data class PairingChallenge(val code: String, val expiresAtMs: Long)

private const val PAIRING_WINDOW_MS = 120_000L
