package dev.siyovush.parentcast.tv.playlist

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.UUID

/** Anonymous playlist metadata only. Existing playback cookies are neither opened nor modified. */
class YtDlpPlaylistSource(context: Context) : PlaylistSource {
    private val runtime = YtDlpRuntime(context.applicationContext)
    private val parser = YtDlpPlaylistParser()

    override suspend fun fetch(playlistId: String): PlaylistResult = runInterruptible(Dispatchers.IO) {
        require(YouTubePlaylistIdParser.isSupportedId(playlistId))
        runtime.ensureInitialized()
        val processId = "parentcast-playlist-${UUID.randomUUID()}"
        try {
            val response = YoutubeDL.getInstance().execute(createRequest(playlistId), processId, false, null)
            if (response.out.length > 16 * 1024 * 1024) {
                PlaylistResult("", emptyList(), PlaylistStatus.ERROR, "Слишком большой ответ метаданных плейлиста.", null)
            } else {
                parser.parse(response.out, response.err, response.exitCode)
            }
        } catch (error: YoutubeDL.CanceledException) {
            throw CancellationException("Playlist extraction cancelled", error)
        } catch (error: InterruptedException) {
            throw CancellationException("Playlist extraction interrupted", error)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PlaylistResult("", emptyList(), PlaylistStatus.ERROR,
                "Не удалось получить публичный плейлист: ошибка сети или yt-dlp. Можно повторить предпросмотр.", null)
        } finally {
            // runInterruptible interrupts the blocking execute/join on cancel, unlike a completion
            // callback on its still-running Job. Library 0.18.1 destroys the process on interruption.
            // The unique ID keeps this cleanup independent from Media3's video extraction.
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    private fun createRequest(playlistId: String): YoutubeDLRequest =
        YoutubeDLRequest("https://www.youtube.com/playlist?list=$playlistId")
            .addOption("--ignore-config")
            .addOption("--flat-playlist")
            .addOption("--skip-download")
            .addOption("--dump-json")
            .addOption("--dump-single-json")
            .addOption("--ignore-errors")
            .addOption("--playlist-end", 501)
            .addOption("--socket-timeout", 15)
            .addOption("--retries", 1)
            .addOption("--extractor-retries", 1)
            .addOption("--no-cache-dir")
            .addOption("--no-progress")
}
