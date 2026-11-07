package dev.siyovush.parentcast.tv.media

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import dev.siyovush.parentcast.tv.youtube.*
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpResponseParser
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpRuntime
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** VK always resolves anonymously; the YouTube cookie file is never passed to this process. */
class VkVideoSource(context: Context) : YouTubeSource {
    private val runtime = YtDlpRuntime(context.applicationContext)
    override suspend fun resolve(videoId: String): ResolvedVideo = VkVideoParser().parse(fetch(videoId),videoId)
    internal suspend fun preview(videoId:String)=VideoQualities.parse(fetch(videoId),videoId,true)
    private suspend fun fetch(videoId: String): JsonNode = withContext(Dispatchers.IO) {
        require(OnlineMediaIds.isVk(videoId))
        runtime.ensureInitialized()
        val id = "parentcast-vk-${UUID.randomUUID()}"
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { YoutubeDL.getInstance().destroyProcessById(id) } }
        }
        try {
            coroutineContext.ensureActive()
            val request = YoutubeDLRequest(OnlineMediaIds.videoUrl(videoId))
                .addOption("--ignore-config").addOption("--no-cache-dir").addOption("--no-playlist")
                .addOption("--use-extractors", "^vk$").addOption("--skip-download").addOption("--dump-single-json")
                .addOption("--socket-timeout",20).addOption("--retries",1).addOption("--extractor-retries",1)
                .addOption("-f", "bestvideo[vcodec^=avc1][height<=1080][protocol=https]+bestaudio[acodec^=mp4a][protocol=https]")
            val response = YoutubeDL.getInstance().execute(request,id,false,null)
            require(response.out.length <= 2 * 1024 * 1024)
            YoutubeDL.objectMapper.readTree(response.out)
        } catch (e: CancellationException) { throw e }
          catch (e: YoutubeDL.CanceledException) { throw CancellationException("VK cancelled",e) }
          catch (e: InterruptedException) { throw CancellationException("VK interrupted",e) }
          catch (e: YouTubeSourceException) { throw e }
          catch (e: Exception) {
            val d=e.safeDiagnostic();val text=d.lowercase()
            val kind=when {
                listOf("private", "login", "log in", "sign in", "unavailable", "not found", "removed").any { it in text } -> PlaybackErrorKind.UNAVAILABLE
                listOf("format", "unsupported", "no suitable extractor").any { it in text } -> PlaybackErrorKind.UNSUPPORTED
                else -> PlaybackErrorKind.NETWORK
            }
            throw YouTubeSourceException(PlaybackFailure(kind,diagnostic="VK: $d"),e)
        } finally { cancellation.cancel() }
    }
}

class VkVideoParser {
    fun parse(root: JsonNode, id: String): ResolvedVideo {
        fun checkVk(value: Boolean) { if(!value) throw YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.UNSUPPORTED,diagnostic="VK: требуется завершённое доступное без входа видео H.264/AAC")) }
        checkVk(OnlineMediaIds.isVk(id) && root.path("id").asText()==id.removePrefix("vk:") && root.path("extractor_key").asText()=="VK")
        checkVk(root.path("is_live").isBoolean && !root.path("is_live").asBoolean() && !root.path("was_live").asBoolean(false))
        checkVk(root.path("duration").asDouble(0.0).let { it.isFinite() && it > 0 && it < 315_360_000 })
        checkVk(root.path("live_status").asText() !in setOf("is_live","is_upcoming","post_live"))
        checkVk(root.path("availability").takeUnless { it.isMissingNode || it.isNull }?.asText() in setOf(null,"public"))
        checkVk(root.path("release_timestamp").asLong(0) <= System.currentTimeMillis()/1000)
        checkVk(!root.path("has_drm").asBoolean(false) && root.path("requested_formats").all { !it.path("has_drm").asBoolean(false) })
        val parsed = YtDlpResponseParser().parse(root,id)
        fun spec(s: StreamSpec): StreamSpec {
            checkVk(runCatching { VkMediaPolicy.allows(s.url.toHttpUrl()) }.getOrDefault(false))
            return s.copy(provider=MediaProvider.VK, headers=s.headers.filterKeys { it.lowercase() in setOf("user-agent","accept","accept-language") } +
                mapOf("Origin" to "https://vkvideo.ru", "Referer" to OnlineMediaIds.videoUrl(id)))
        }
        return parsed.copy(videoId=id,video=spec(parsed.video),audio=parsed.audio?.let(::spec),selectionDescription="VK, ${parsed.selectionDescription}")
    }
}

class OnlineVideoSource(private val youtube: YouTubeSource, private val vk: YouTubeSource) : YouTubeSource {
    override suspend fun resolve(videoId: String): ResolvedVideo =
        if(OnlineMediaIds.isVk(videoId)) vk.resolve(videoId) else youtube.resolve(videoId)
}
