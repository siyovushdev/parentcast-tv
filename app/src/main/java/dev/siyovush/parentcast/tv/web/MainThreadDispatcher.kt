package dev.siyovush.parentcast.tv.web

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface CallDispatcher {
    fun <T> call(block: () -> T): T
}

class MainThreadDispatcher(private val handler: Handler) : CallDispatcher {
    override fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: Any? = null
        var failure: Throwable? = null
        handler.post {
            try {
                value = block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw TimeoutException("Main thread command timed out")
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
