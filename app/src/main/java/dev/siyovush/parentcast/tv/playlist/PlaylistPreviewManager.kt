package dev.siyovush.parentcast.tv.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A bounded, token-owner-scoped cache. It never mutates the playback queue. */
class PlaylistPreviewManager(
    private val source: PlaylistSource,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 120_000,
    private val ttlMs: Long = 15 * 60_000,
) {
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val records = linkedMapOf<String, Record>()
    private var closed = false

    @Synchronized
    fun start(owner: String, url: String): PlaylistPreview {
        check(!closed) { "Preview manager closed" }
        val playlistId = dev.siyovush.parentcast.tv.media.OnlineMediaIds.parsePlaylist(url)
        cleanup()
        // One active extraction per browser prevents accidental resource exhaustion while preserving
        // other owners' ready previews. Old results cannot overwrite the new preview.
        records.values.filter { it.owner == owner && it.preview.state == PreviewState.LOADING }.forEach(::cancelRecord)
        check(records.values.count { it.preview.state == PreviewState.LOADING } < 4) { "Too many active previews" }
        while (records.size >= 16) {
            val oldest = records.values.firstOrNull { it.preview.state != PreviewState.LOADING }
                ?: throw IllegalStateException("Too many previews")
            records.remove(oldest.preview.id)
        }
        val preview = PlaylistPreview(UUID.randomUUID().toString(), PreviewState.LOADING, playlistKey = playlistId)
        val record = Record(owner, preview, now())
        records[preview.id] = record
        record.job = workerScope.launch {
            val result = try {
                withTimeoutOrNull(timeoutMs) { source.fetch(playlistId) }
                    ?: PlaylistResult("", emptyList(), PlaylistStatus.ERROR, "Истекло время получения плейлиста. Повторите предпросмотр.", null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PlaylistResult("", emptyList(), PlaylistStatus.ERROR, "Ошибка получения плейлиста.", null)
            }
            synchronized(this@PlaylistPreviewManager) {
                if (records[preview.id] === record && record.preview.state == PreviewState.LOADING) {
                    record.preview = preview.copy(state = PreviewState.READY, result = result)
                }
            }
        }
        return preview
    }

    @Synchronized
    fun get(owner: String, id: String): PlaylistPreview? {
        cleanup()
        return records[id]?.takeIf { it.owner == owner }?.preview
    }

    @Synchronized
    fun containsVideo(owner: String, videoId: String): Boolean {
        cleanup()
        return records.values.any { it.owner == owner && it.preview.result?.entries?.any { e -> e.videoId == videoId } == true }
    }

    @Synchronized
    fun cancel(owner: String, id: String): PlaylistPreview? {
        cleanup()
        val record = records[id]?.takeIf { it.owner == owner } ?: return null
        cancelRecord(record)
        return record.preview
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        records.values.forEach(::cancelRecord)
        records.clear()
        workerScope.cancel()
    }

    private fun cancelRecord(record: Record) {
        record.preview = record.preview.copy(state = PreviewState.CANCELLED, result = null)
        record.job?.cancel()
    }

    private fun cleanup() {
        val expired = records.values.filter { now() - it.createdAtMs >= ttlMs }
        expired.forEach {
            cancelRecord(it)
            records.remove(it.preview.id)
        }
    }

    private class Record(
        val owner: String,
        var preview: PlaylistPreview,
        val createdAtMs: Long,
        var job: Job? = null,
    )
}
