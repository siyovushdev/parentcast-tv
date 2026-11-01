package dev.siyovush.parentcast.tv.web

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinSecurity {
    private const val ITERATIONS = 120_000
    private val random = SecureRandom()

    fun isValidPin(pin: String): Boolean = Regex("[0-9]{6,12}").matches(pin)

    fun hashPin(pin: String): String {
        require(isValidPin(pin))
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(pin, salt, ITERATIONS)
        return "v1:$ITERATIONS:${salt.toBase64()}:${hash.toBase64()}"
    }

    fun verifyPin(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != "v1") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0) return false
        val salt = parts[2].decodeBase64() ?: return false
        val expected = parts[3].decodeBase64() ?: return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.decodeBase64(): ByteArray? = try {
        Base64.getUrlDecoder().decode(this)
    } catch (error: IllegalArgumentException) {
        null
    }
}

interface PinSettings {
    fun hasPin(): Boolean
    fun pinRecord(): String?
    fun savePinRecord(record: String)
}

class SharedPinSettings(context: Context) : PinSettings {
    private val preferences = context.getSharedPreferences("parent-auth", Context.MODE_PRIVATE)

    override fun hasPin(): Boolean = !pinRecord().isNullOrBlank()
    override fun pinRecord(): String? = preferences.getString("pin_record", null)
    override fun savePinRecord(record: String) {
        preferences.edit().putString("pin_record", record).commit()
    }
}
