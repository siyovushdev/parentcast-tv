package dev.siyovush.parentcast.tv.youtube

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CookieHeaderCodecTest {
    @Test
    fun mergesConsentAndReplacesOnlyDuplicateNames() {
        val sessionCookie = Cookie.Builder()
            .name("SAPISID")
            .value("dummy-session")
            .domain("youtube.com")
            .build()

        val merged = CookieHeaderCodec.merge(
            listOf("SOCS=CAE=; SAPISID=old-dummy"),
            listOf(sessionCookie),
        )

        assertEquals("SOCS=CAE=; SAPISID=dummy-session", merged)
        assertFalse(merged!!.contains("old-dummy"))
    }

    @Test
    fun keepsExistingHeaderWhenSessionIsEmpty() {
        assertEquals("SOCS=CAE=", CookieHeaderCodec.merge(listOf("SOCS=CAE="), emptyList()))
    }

    @Test
    fun emptyInputProducesNoHeader() {
        assertNull(CookieHeaderCodec.merge(emptyList(), emptyList()))
    }
}
