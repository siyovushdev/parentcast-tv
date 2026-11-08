package dev.siyovush.parentcast.tv.playback

import android.content.Context
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import org.json.JSONObject
import java.io.File

/** ADB/run-as one-shot acceptance probe. No exported component or HTTP route. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackRecoveryProbe {
    fun poll(context:Context,coordinator:PlaybackCoordinator,online:(String)->Boolean) {
        val file=File(context.noBackupFilesDir,"debug-online-recovery-probe.json")
        if(!file.isFile)return
        runCatching {
            require(file.length() in 1..1024)
            val request=JSONObject(file.readText());check(file.delete())
            val snapshot=coordinator.currentSnapshot();val q=snapshot.queueState
            if(q.currentItem?.queueItemId!=request.getString("queueItemId") || snapshot.generation!=request.getLong("generation") ||
                q.intent!=PlaybackIntent.PLAY || snapshot.status!=PlaybackStatus.PLAYING || !online(q.currentItem!!.videoId))return
            val failure=when(request.getString("kind")) {
                "CONTAINER" -> classifyPlaybackError(PlaybackException("debug probe",
                    ParserException.createForUnsupportedContainerFeature("Skipping atom with length > 2147483647 (unsupported)."),
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),true)
                "NETWORK" -> PlaybackFailure(PlaybackErrorKind.NETWORK,diagnostic="DEBUG_NETWORK_PROBE",onlineRecovery=OnlineRecovery.NETWORK)
                else -> return
            }
            coordinator.onError(snapshot.generation,failure)
        }.onFailure { file.delete() }
    }
}
