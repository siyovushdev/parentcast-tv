package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpStatusTest {
    @Test
    fun misdirectedRequestHasRealTransportStatus() {
        assertEquals(421, responseStatus(421).requestStatus)
        assertEquals("421 Misdirected Request", responseStatus(421).description)
        assertEquals(409, responseStatus(409).requestStatus)
        assertEquals(500, responseStatus(599).requestStatus)
    }
}
