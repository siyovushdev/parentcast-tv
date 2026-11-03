package dev.siyovush.parentcast.tv.local

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import dev.siyovush.parentcast.tv.prepared.LocalMediaInfo
import java.io.File

internal fun validateLocalMedia(file: File): LocalMediaInfo {
    val extractor = MediaExtractor()
    val metadata = MediaMetadataRetriever()
    try {
        extractor.setDataSource(file.path)
        val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
        check(extractor.drmInitData == null) { "UNSUPPORTED_MEDIA" }
        val video = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        check(video.size == 1 && audio.size <= 1) { "UNSUPPORTED_MEDIA" }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        check((video + audio).all { codecs.findDecoderForFormat(it) != null }) { "UNSUPPORTED_CODEC" }
        metadata.setDataSource(file.path)
        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        check(duration > 0 && mime in setOf("video/mp4", "video/x-matroska", "video/webm", "video/3gpp")) { "UNSUPPORTED_MEDIA" }
        return LocalMediaInfo((duration + 999) / 1000, mime)
    } catch (error: IllegalStateException) { throw error }
      catch (_: Exception) { error("UNSUPPORTED_MEDIA") }
    finally { extractor.release(); metadata.release() }
}
