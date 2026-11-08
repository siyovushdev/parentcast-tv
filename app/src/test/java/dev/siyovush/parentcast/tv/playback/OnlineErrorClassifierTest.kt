package dev.siyovush.parentcast.tv.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import dev.siyovush.parentcast.tv.streaming.RangeFailure
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[33],manifest=org.robolectric.annotation.Config.NONE)
class OnlineErrorClassifierTest {
    @Test fun observedMp4AtomParserFailureCanReopenOnlyOnlineAndKeepsSafeReason() {
        val cause=ParserException.createForUnsupportedContainerFeature("Skipping atom with length > 2147483647 (unsupported).")
        val error=PlaybackException("Source error",cause,PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(OnlineRecovery.CONTAINER,classifyPlaybackError(error,true).onlineRecovery)
        assertEquals("ONLINE_CONTAINER_READ",classifyPlaybackError(error,true).diagnostic)
        assertEquals(OnlineRecovery.NONE,classifyPlaybackError(error,false).onlineRecovery)
        assertEquals(PlaybackErrorKind.PLAYBACK,classifyPlaybackError(error,false).kind)
        assertEquals("FILE_DAMAGED",classifyPlaybackError(error,false).diagnostic)
    }
    @Test fun transportPolicyFaultsAreNotMisreportedAsRetryableNetwork() {
        for(reason in listOf("RANGE_UNSUPPORTED","INVALID_RANGE","UNSUPPORTED_MEDIA_HOST","REPRESENTATION_CHANGED","UNAVAILABLE","YOUTUBE_AUTH_REQUIRED")) {
            val f=classifyPlaybackError(PlaybackException("source",RangeFailure(reason),PlaybackException.ERROR_CODE_IO_UNSPECIFIED),true)
            assertEquals(reason,OnlineRecovery.NONE,f.onlineRecovery);assertEquals(reason,f.diagnostic)
        }
        val error=PlaybackException("timeout",SocketTimeoutException(),PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(OnlineRecovery.NETWORK,classifyPlaybackError(error,true).onlineRecovery)
        assertEquals(OnlineRecovery.NONE,classifyPlaybackError(error,false).onlineRecovery)
        assertEquals(OnlineRecovery.NONE,classifyPlaybackError(PlaybackException("decoder",java.io.IOException(),PlaybackException.ERROR_CODE_DECODING_FAILED),true).onlineRecovery)
    }
}
