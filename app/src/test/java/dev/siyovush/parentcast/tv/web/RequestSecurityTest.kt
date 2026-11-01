package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSecurityTest {
    private val security = RequestSecurity(setOf("192.168.68.121", "127.0.0.1", "::1"), 8765)

    @Test
    fun acceptsOnlyPanelHostsAndExpectedPort() {
        assertTrue(security.isHostAllowed("192.168.68.121:8765"))
        assertTrue(security.isHostAllowed("127.0.0.1:8765"))
        assertTrue(security.isHostAllowed("[::1]:8765"))
        assertFalse(security.isHostAllowed("192.168.68.121:9999"))
        assertFalse(security.isHostAllowed("evil.example:8765"))
        assertFalse(security.isHostAllowed(null))
    }

    @Test
    fun originAndRefererAreRestricted() {
        assertTrue(security.isOriginAllowed("http://192.168.68.121:8765"))
        assertFalse(security.isOriginAllowed("https://evil.example"))
        assertTrue(security.isRefererAllowed("http://192.168.68.121:8765/queue"))
        assertFalse(security.isRefererAllowed("https://evil.example/attack"))
    }

    @Test
    fun stateChangesRequireCustomHeaderAndSameSite() {
        val valid = mapOf(
            "x-parentcast-client" to "m1",
            "origin" to "http://192.168.68.121:8765",
            "referer" to "http://192.168.68.121:8765/queue",
        )
        assertTrue(security.isStateChangeAllowed(valid))
        assertFalse(security.isStateChangeAllowed(valid - "x-parentcast-client"))
        assertFalse(security.isStateChangeAllowed(valid + ("origin" to "https://evil.example")))
    }
}
