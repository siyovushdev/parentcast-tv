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
class YtDlpPlaylistSource(private val context: Context) : PlaylistSource {
    private val runtime = YtDlpRuntime(context.applicationContext)
    private val parser = YtDlpPlaylistParser()

    override suspend fun fetch(playlistId: String): PlaylistResult = runInterruptible(Dispatchers.IO) {
        val vk=dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVkPlaylist(playlistId)
        require(vk || YouTubePlaylistIdParser.isSupportedId(playlistId))
        runtime.ensureInitialized()
        val processId = "parentcast-playlist-${UUID.randomUUID()}"
        try {
            val response = YoutubeDL.getInstance().execute(createRequest(playlistId), processId, false, null)
            if (response.out.length > 16 * 1024 * 1024) {
                PlaylistResult("", emptyList(), PlaylistStatus.ERROR, "Слишком большой ответ метаданных плейлиста.", null)
            } else {
                if(vk) VkPlaylistParser().parse(response.out,response.err,response.exitCode,playlistId) else parser.parse(response.out, response.err, response.exitCode)
            }
        } catch (error: YoutubeDL.CanceledException) {
            throw CancellationException("Playlist extraction cancelled", error)
        } catch (error: InterruptedException) {
            throw CancellationException("Playlist extraction interrupted", error)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            PlaylistResult("", emptyList(), PlaylistStatus.ERROR,
                "Не удалось получить плейлист YouTube/VK: ошибка сети или yt-dlp. Можно повторить предпросмотр.", null)
        } finally {
            // runInterruptible interrupts the blocking execute/join on cancel, unlike a completion
            // callback on its still-running Job. Library 0.18.1 destroys the process on interruption.
            // The unique ID keeps this cleanup independent from Media3's video extraction.
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    private fun createRequest(playlistId: String): YoutubeDLRequest =
        YoutubeDLRequest(if(dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVkPlaylist(playlistId)) dev.siyovush.parentcast.tv.media.OnlineMediaIds.playlistUrl(playlistId) else "https://www.youtube.com/playlist?list=$playlistId")
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
            .addOption("--no-progress").also { request ->
                if(dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVkPlaylist(playlistId)) {
                    request.addOption("--plugin-dirs", installVkPlugin()).addOption("--use-extractors", "vk:uservideos.*")
                }
            }

    @Synchronized
    private fun installVkPlugin(): String {
        val root=java.io.File(context.noBackupFilesDir,"vk-metadata-plugins")
        val target=java.io.File(root,"parentcast/yt_dlp_plugins/extractor/parentcast_vk.py")
        val bytes=context.resources.openRawResource(dev.siyovush.parentcast.tv.R.raw.parentcast_vk).use { it.readBytes() }
        if(!target.isFile || !target.readBytes().contentEquals(bytes)) {
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            val temp=java.io.File(target.path+".tmp")
            java.io.FileOutputStream(temp).use { it.write(bytes);it.fd.sync() }
            check(temp.renameTo(target));target.setReadable(true,true)
        }
        return root.path
    }
}
