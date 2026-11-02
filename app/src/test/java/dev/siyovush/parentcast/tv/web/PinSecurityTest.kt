package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSecurityTest {
    @Test
    fun hashVerifiesPinWithoutStoringPlaintext() {
        val pin = "739251"
        val record = PinSecurity.hashPin(pin)

        assertTrue(PinSecurity.verifyPin(pin, record))
        assertFalse(PinSecurity.verifyPin("000000", record))
        assertFalse(record.contains(pin))
        assertNotEquals(record, PinSecurity.hashPin(pin))
    }

    @Test
    fun pinMustBeNumericAndHaveReasonableLength() {
        assertTrue(PinSecurity.isValidPin("123456"))
        assertFalse(PinSecurity.isValidPin("1234"))
        assertFalse(PinSecurity.isValidPin("abcdef"))
        assertFalse(PinSecurity.isValidPin("1234567890123"))
    }
}
