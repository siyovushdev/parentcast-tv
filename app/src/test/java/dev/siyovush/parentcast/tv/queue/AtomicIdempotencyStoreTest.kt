package dev.siyovush.parentcast.tv.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicIdempotencyStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun responseRoundTripsWithoutDuplication() {
        val file = temporaryFolder.newFile("idempotency.json")
        val store = AtomicIdempotencyStore(file)
        val response = StoredResponse(code = 200, body = "{\"accepted\":true}", createdAtMs = System.currentTimeMillis())

        store.put("device|command", response)

        assertEquals(response, store.get("device|command"))
        assertNull(store.get("device|other"))
    }

    @Test
    fun expiredResponseIsIgnored() {
        val file = temporaryFolder.newFile("idempotency.json")
        val store = AtomicIdempotencyStore(file, ttlMs = 1)

        store.put("device|old", StoredResponse(200, "old", 1))

        assertNull(store.get("device|old"))
    }
}
