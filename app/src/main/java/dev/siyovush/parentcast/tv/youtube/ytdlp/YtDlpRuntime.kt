package dev.siyovush.parentcast.tv.youtube.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import dev.siyovush.parentcast.tv.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class YtDlpRuntime(private val context: Context) {
    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext)
            val directory = File(
                appContext.noBackupFilesDir,
                "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}",
            )
            val target = File(directory, YoutubeDL.ytdlpBin)
            if (sha256(target) != YTDLP_SHA256) installPinnedBinary(directory, target)
            initialized = true
        }
    }

    private fun installPinnedBinary(directory: File, target: File) {
        directory.mkdirs()
        val temporary = File.createTempFile("yt-dlp", ".tmp", directory)
        try {
            context.resources.openRawResource(R.raw.ytdlp_2026_10_26).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (sha256(temporary) != YTDLP_SHA256) {
                throw IOException("Pinned yt-dlp checksum mismatch")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Could not install pinned yt-dlp")
            }
            target.setReadable(true, true)
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val YTDLP_SHA256 =
            "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3"
        private val lock = Any()

        @Volatile
        private var initialized = false
    }
}
