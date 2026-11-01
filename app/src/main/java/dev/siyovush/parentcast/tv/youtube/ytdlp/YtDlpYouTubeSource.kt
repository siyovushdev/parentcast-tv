package dev.siyovush.parentcast.tv.youtube.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.YouTubeSession
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import dev.siyovush.parentcast.tv.youtube.safeDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

class YtDlpYouTubeSource(
    context: Context,
    private val session: YouTubeSession,
) : YouTubeSource {
    private val appContext = context.applicationContext
    private val runtime = YtDlpRuntime(appContext)
    private val parser = YtDlpResponseParser()
    private val cookieFile = File(appContext.filesDir, "youtube-cookies.txt")

    override suspend fun resolve(videoId: String): ResolvedVideo = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        runtime.ensureInitialized()
        val processId = "parentcast-${UUID.randomUUID()}"
        var completed = false
        coroutineContext[Job]?.invokeOnCompletion {
            if (!completed) YoutubeDL.getInstance().destroyProcessById(processId)
        }
        try {
            val request = createRequest(videoId)
            val response = YoutubeDL.getInstance().execute(request, processId, false, null)
            val root = YoutubeDL.objectMapper.readTree(response.out)
            parser.parse(root, videoId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: YoutubeDL.CanceledException) {
            throw CancellationException("yt-dlp request cancelled")
        } catch (error: InterruptedException) {
            throw CancellationException("yt-dlp request interrupted")
        } catch (error: Throwable) {
            throw mapError(error)
        } finally {
            completed = true
        }
    }

    private fun createRequest(videoId: String): YoutubeDLRequest {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            .addOption("--dump-json")
            .addOption("--no-playlist")
            .addOption("--ignore-config")
            .addOption("--no-call-home")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", 20)
            .addOption("--retries", 2)
            .addOption("--fragment-retries", 2)
            .addOption("-f", FORMAT_SELECTOR)
        if (session.isEnabled) {
            request.addOption("--cookies", cookieFile.absolutePath)
        }
        return request
    }

    private fun mapError(error: Throwable): YouTubeSourceException {
        val diagnostic = error.safeDiagnostic()
        val normalized = diagnostic.lowercase()
        val loginRequired = "login_required" in normalized ||
            "sign in to confirm" in normalized ||
            "not a bot" in normalized
        val network = "unknownhostexception" in normalized ||
            "sockettimeoutexception" in normalized ||
            "connectexception" in normalized ||
            "network is unreachable" in normalized ||
            "connection reset" in normalized
        val kind = when {
            loginRequired -> PlaybackErrorKind.EXTRACTOR
            network -> PlaybackErrorKind.NETWORK
            else -> PlaybackErrorKind.EXTRACTOR
        }
        return YouTubeSourceException(
            PlaybackFailure(
                kind = kind,
                diagnostic = diagnostic,
                stopRequested = loginRequired,
            ),
            error,
        )
    }

    companion object {
        private const val FORMAT_SELECTOR =
            "bestvideo[vcodec^=avc1][height<=1080][ext=mp4]+" +
                "bestaudio[acodec^=mp4a][ext=m4a]/" +
                "best[vcodec^=avc1][acodec^=mp4a][height<=1080][ext=mp4]"
    }
}
