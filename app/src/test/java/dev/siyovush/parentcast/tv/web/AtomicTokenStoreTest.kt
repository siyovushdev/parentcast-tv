package dev.siyovush.parentcast.tv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTokenStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun tokenIsVerifiedThenRevoked() {
        val store = AtomicTokenStore(temporaryFolder.newFile("devices.json"))
        val (record, token) = store.create("Test browser")

        assertEquals(record.deviceId, store.verify(token)?.deviceId)
        assertTrue(store.revoke(record.deviceId))
        assertNull(store.verify(token))
        assertFalse(store.revoke(record.deviceId))
        assertNotNull(store.list().single().revokedAtMs)
    }
}
