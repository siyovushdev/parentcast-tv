package dev.siyovush.parentcast.tv.prepared

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal class MediaIntegrityFailure(val code: String) : Exception(code)
internal const val INTEGRITY_BLOCK_BYTES = 4 * 1024 * 1024
internal data class IntegrityBlock(val bytes: Int, val sha256: String)

/** Digests originate from received bytes, before they are read back from storage. */
internal class IncomingTrackIntegrity private constructor(
    private val whole: MessageDigest,
    blocks: List<IntegrityBlock>,
) {
    private val completed = blocks.toMutableList()
    private val pending = MessageDigest.getInstance("SHA-256")
    var pendingBytes = 0
        private set
    fun accept(bytes: ByteArray, count: Int) {
        whole.update(bytes, 0, count); pending.update(bytes, 0, count); pendingBytes += count
    }
    fun checkpoint(): List<IntegrityBlock> {
        if (pendingBytes > 0) {
            check(completed.size < 16_384) { "RESUME_CHECKPOINT_LIMIT" }
            completed += IntegrityBlock(pendingBytes, hex(pending.digest()))
            pendingBytes = 0
        }
        return completed.toList()
    }
    fun digest(): String = hex(whole.digest())

    companion object {
        fun fresh() = IncomingTrackIntegrity(MessageDigest.getInstance("SHA-256"), emptyList())
        /** A crash may leave an uncheckpointed tail. Only independently verified bytes resume. */
        fun restore(file: File, blocks: List<IntegrityBlock>, checkCancellation: () -> Unit): IncomingTrackIntegrity? {
            val whole = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            if (file.length() < blocks.sumOf { it.bytes.toLong() }) return null
            FileInputStream(file).use { input ->
                for (block in blocks) {
                    var remaining = block.bytes
                    val digest = MessageDigest.getInstance("SHA-256")
                    while (remaining > 0) {
                        checkCancellation()
                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n < 0) return null
                        whole.update(buffer, 0, n); digest.update(buffer, 0, n); remaining -= n
                    }
                    if (hex(digest.digest()) != block.sha256) return null
                }
            }
            return IncomingTrackIntegrity(whole, blocks)
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Run the player's MP4 parser over every sample. This is structural validation, not decoding. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun verifyMp4Track(file: File, audio: Boolean, durationSeconds: Long, checkCancellation: () -> Unit = {}) {
    class Track : TrackOutput by DummyTrackOutput() {
        var mime: String? = null
        var samples = 0L
        var lastTimeUs = Long.MIN_VALUE
        override fun format(format: Format) { mime = format.sampleMimeType }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++; lastTimeUs = maxOf(lastTimeUs, timeUs)
        }
    }
    val tracks = mutableMapOf<Int, Track>()
    val output = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track() }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
    val candidates = listOf<Extractor>(FragmentedMp4Extractor(), Mp4Extractor())
    try {
        FileInputStream(file).use { stream ->
            val length = file.length()
            fun input(position: Long): DefaultExtractorInput {
                checkCancellation()
                if (position !in 0..length) throw MediaIntegrityFailure("MEDIA_CORRUPT")
                stream.channel.position(position)
                return DefaultExtractorInput({ bytes, offset, count -> checkCancellation(); stream.read(bytes, offset, count) }, position, length)
            }
            val extractor = candidates.firstOrNull { it.sniff(input(0)) } ?: throw MediaIntegrityFailure("MEDIA_CORRUPT")
            extractor.init(output)
            var source = input(0)
            val seek = PositionHolder()
            while (true) {
                checkCancellation()
                when (extractor.read(source, seek)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> source = input(seek.position)
                }
            }
        }
        val mime = if (audio) "audio/mp4a-latm" else "video/avc"
        val matching = tracks.values.filter { it.mime == mime }
        val requiredTime = (durationSeconds - 5).coerceAtLeast(0) * C.MICROS_PER_SECOND
        if (matching.isEmpty() || matching.any { it.samples == 0L || it.lastTimeUs < requiredTime })
            throw MediaIntegrityFailure("MEDIA_CORRUPT")
    } catch (_: ParserException) {
        throw MediaIntegrityFailure("MEDIA_CORRUPT")
    } catch (_: EOFException) {
        throw MediaIntegrityFailure("MEDIA_CORRUPT")
    } finally {
        candidates.forEach { it.release() }
    }
}
