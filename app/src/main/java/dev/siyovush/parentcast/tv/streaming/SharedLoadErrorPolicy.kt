package dev.siyovush.parentcast.tv.streaming

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** A selected streaming item waits through network outages; STOP/replacement cancels its loader. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SharedLoadErrorPolicy:DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType:Int)=Int.MAX_VALUE
    override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long {
        val reasons=generateSequence<Throwable>(info.exception){it.cause}.take(10).filterIsInstance<RangeFailure>().map { it.reason }.toList()
        if(reasons.any { it in setOf("NETWORK_RETRY","URL_EXPIRED","PRIORITY_YIELD","TRANSFER_CANCELLED") })
            return (1_000L * info.errorCount.coerceAtLeast(1)).coerceAtMost(30_000L)
        // Cache, validation, representation, storage and codec faults are never hidden in an infinite spinner.
        return if(reasons.isNotEmpty() || info.errorCount>3) C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }
}
