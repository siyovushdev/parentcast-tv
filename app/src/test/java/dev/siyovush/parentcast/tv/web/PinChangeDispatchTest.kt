package dev.siyovush.parentcast.tv.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PinChangeDispatchTest {
    @Test
    fun slowPinWriteLeavesUiAndHasPinResponsive() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writerThread = ""
        val settings = object : PinSettings {
            @Volatile var record = PinSecurity.hashPin("739251")
            override fun hasPin() = true
            override fun pinRecord() = record
            override fun savePinRecord(record: String) {
                writerThread = Thread.currentThread().name
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                this.record = record
            }
        }
        val tokens = object : TokenStore {
            override fun create(label: String): Pair<DeviceRecord, String> = error("No new token expected")
            override fun verify(token: String): DeviceRecord? = null
            override fun revoke(deviceId: String) = false
            override fun list() = emptyList<DeviceRecord>()
        }
        val manager = PairingManager(settings, tokens)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val saving = CoroutineScope(dispatcher).async { manager.changePin("839251", "739251") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertTrue(executor.submit<Boolean> { manager.hasPin() }.get(1, TimeUnit.SECONDS))
                assertNotEquals("test-ui", writerThread)
            } finally {
                release.countDown()
            }
            assertTrue(saving.await())
            assertTrue(PinSecurity.verifyPin("839251", settings.record))
            val unchanged = settings.record
            assertFalse(manager.changePin("939251", "739251"))
            assertEquals(unchanged, settings.record)
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }
}
