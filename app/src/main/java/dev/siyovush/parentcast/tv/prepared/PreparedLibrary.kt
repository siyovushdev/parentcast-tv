package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal fun interface TrackDownloader {
    suspend fun download(spec: StreamSpec, target: File, allowance: Long, progress: (Long, Long) -> Unit): LocalTrack
}
internal class PreparedLibrary(
    private val store: PreparedStore, private val source: YouTubeSource,
    private val downloader: TrackDownloader, private val scope: CoroutineScope,
    private val validate: (File, Boolean, Long) -> Unit,
    private val wakeService: () -> Unit = {},
    val limit: Long = LIBRARY_LIMIT,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val disk = Mutex()
    private val worker = Mutex()
    private val revision = MutableStateFlow(0L)
    private var state = store.read()
    @Volatile private var active: Job? = null
    @Volatile private var activeId: String? = null
    init { if (state.deleting.isNotEmpty()) scope.launch { finishDeletion() } }
    fun storageStats(): Pair<Long, Long> = store.usedBytes() to store.directory.usableSpace
    fun snapshot(): PreparationState = synchronized(lock) { state }
    private fun update(transform: (PreparationState) -> PreparationState) {
        synchronized(lock) { state = transform(state); revision.value++ }
    }
    private suspend fun persist() = disk.withLock { store.write(snapshot()) }
    private fun persistLater() { scope.launch { try { persist() } catch (_: Exception) { update { it.copy(storageError = true) } } } }
    fun serviceUnavailable() { update { it.copy(paused = true) }; persistLater() }
    fun resumePending() { if (hasPending()) wakeService() }
    fun schedule(ids: List<String>) {
        require(ids.size <= 10_000 && ids.all(VIDEO_ID::matches))
        if (snapshot().plan == ids) return
        update { s -> s.copy(plan = ids, entries = s.entries + ids.distinct().filterNot { it in s.entries }.associateWith { PreparedEntry(it) }) }
        persistLater()
        if (activeId?.let { it !in ids } == true) active?.cancel()
        val current = snapshot()
        if (current.enabled && !current.paused) wakeService()
    }
    suspend fun configure(enabled: Boolean, ids: List<String>) {
        require(ids.size <= 10_000 && ids.all(VIDEO_ID::matches))
        check(!snapshot().storageError)
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        if (!enabled) active?.cancel()
        update { s -> s.copy(enabled = enabled, paused = false, plan = ids,
            entries = (s.entries + ids.distinct().filterNot { it in s.entries }.associateWith { PreparedEntry(it) }).mapValues { (id, e) ->
                if (enabled && id in ids && e.status == PreparationStatus.DELETED) PreparedEntry(id, title = e.title) else e
            }) }
        persist()
        if (enabled) wakeService()
    }
    suspend fun pause() {
        update { it.copy(paused = true) }; active?.cancel(); persist()
    }
    suspend fun retry() {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        update { s -> s.copy(paused = false, entries = s.entries.mapValues { (_, e) ->
            if (e.status == PreparationStatus.ERROR) e.copy(status = PreparationStatus.WAITING, error = "") else e
        }) }; persist(); if (snapshot().enabled) wakeService()
    }
    suspend fun deleteMedia(id: String? = null) {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        require(id == null || VIDEO_ID.matches(id))
        val targets = if (id == null) (snapshot().entries.keys + store.mediaIds()).toList() else listOf(id)
        update { it.copy(paused = true, deleting = targets, operationError = "") }
        active?.cancel()
        try { persist() } catch (error: Exception) {
            update { it.copy(deleting = emptyList(), operationError = "DELETE_FAILED") }
            throw error
        }
        scope.launch { finishDeletion() }
    }
    suspend fun retryDeletion() {
        check(snapshot().deleting.isNotEmpty() && snapshot().operationError.isNotEmpty()) { "BUSY" }
        update { it.copy(operationError = "") }; persist()
        scope.launch { finishDeletion() }
    }
    private suspend fun finishDeletion() = worker.withLock {
        val targets = snapshot().deleting
        try {
            targets.forEach(store::deleteChecked)
            update { s -> s.copy(deleting = emptyList(), operationError = "", entries = s.entries.mapValues { (id, e) ->
                if (id in targets) PreparedEntry(id, PreparationStatus.DELETED, title = e.title, phase = "DELETED") else e
            }) }
            persist()
        } catch (_: Exception) {
            update { it.copy(operationError = "DELETE_FAILED", paused = true) }
            runCatching { persist() }
        }
    }
    suspend fun cleanup(): Int {
        check(worker.tryLock()) { "BUSY" }
        try {
        val removable = snapshot().let { it.entries.keys - it.plan.toSet() }
        removable.forEach(store::delete)
        update { it.copy(entries = it.entries - removable) }; persist(); return removable.size
        } finally { worker.unlock() }
    }
    fun hasPending(): Boolean = snapshot().let { s -> s.enabled && !s.paused && !s.storageError && s.deleting.isEmpty() && s.plan.any { s.entries[it]?.status == PreparationStatus.WAITING } }
    suspend fun runWorker() = worker.withLock {
        active = currentCoroutineContext()[Job]
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val s = snapshot()
                if (!s.enabled || s.paused || s.storageError || s.deleting.isNotEmpty()) break
                val id = s.plan.firstOrNull { s.entries[it]?.status == PreparationStatus.WAITING } ?: break
                activeId = id
                update { it.copy(entries = it.entries + (id to PreparedEntry(id, PreparationStatus.DOWNLOADING, phase = "INFO"))) }; persist()
                try {
                    store.delete(id) // Drop any uncommitted track left by a killed process.
                    val resolved = source.resolve(id)
                    check(resolved.videoId == id && resolved.durationSeconds > 0) { "UNSUPPORTED" }
                    val tracks = mutableListOf<LocalTrack>()
                    val streams = listOfNotNull(resolved.video, resolved.audio)
                    // Expired links are only held in memory for this attempt; never persisted.
                    for ((index, spec) in streams.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (id !in snapshot().plan) throw CancellationException("Queue item removed")
                        if (store.usedBytes() >= limit) evictUnreferenced()
                        val allowance = limit - store.usedBytes()
                        check(allowance > 0) { "NO_SPACE" }
                        val base = tracks.sumOf { it.bytes }
                        update { s1 -> val old = s1.entries.getValue(id)
                            s1.copy(entries = s1.entries + (id to old.copy(title = resolved.title,
                                phase = if (index == 0) "VIDEO" else "AUDIO"))) }
                        val track = downloader.download(spec, store.track(id, index == 1), allowance) { bytes, total ->
                            update { s1 -> val old = s1.entries.getValue(id)
                                val remaining = streams.drop(index + 1).map { it.sizeBytes }
                                val known = total > 0 && remaining.all { it != null && it > 0 }
                                val whole = if (known) runCatching { remaining.fold(Math.addExact(base, total)) { sum, size -> Math.addExact(sum, size!!) } }.getOrDefault(0L) else 0L
                                s1.copy(entries = s1.entries + (id to old.copy(bytes = base + bytes, total = whole, totalKnown = whole > 0))) }
                        }
                        update { s1 -> val old = s1.entries.getValue(id)
                            s1.copy(entries = s1.entries + (id to old.copy(phase = "VERIFY"))) }
                        validate(File(store.directory, track.name), index == 1, resolved.durationSeconds)
                        if (index == 0 && resolved.audio == null) validate(File(store.directory, track.name), true, resolved.durationSeconds)
                        tracks += track
                    }
                    currentCoroutineContext().ensureActive()
                    update { it.copy(entries = it.entries + (id to PreparedEntry(id, PreparationStatus.READY,
                        resolved.title, resolved.durationSeconds, tracks.sumOf { t -> t.bytes }, tracks.sumOf { t -> t.bytes }, tracks = tracks))) }
                    persist()
                } catch (cancelled: CancellationException) {
                    resetCancelled(id)
                    throw cancelled
                } catch (error: Exception) {
                    // Cancelling an HTTP socket can surface as IOException rather than CancellationException.
                    if (!currentCoroutineContext().isActive) {
                        resetCancelled(id)
                        throw CancellationException("Preparation cancelled", error)
                    }
                    onFailure(id, error)
                    store.delete(id)
                    val code = when {
                        error is YouTubeSourceException && error.failure.stopRequested -> "YOUTUBE_AUTH_REQUIRED"
                        error.message == "NO_SPACE" -> "NO_SPACE"
                        error.message == "UNSUPPORTED" -> "UNSUPPORTED"
                        else -> "DOWNLOAD_FAILED"
                    }
                    if (code == "NO_SPACE" && evictUnreferenced() > 0) {
                        update { s1 -> s1.copy(entries = s1.entries + (id to PreparedEntry(id))) }; persist()
                        continue // One retry after reclaiming watched/unreferenced files; never evict the remaining plan.
                    }
                    update { s1 -> val old = s1.entries.getValue(id)
                        s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.ERROR, error = code, bytes = 0, total = 0, tracks = emptyList(), totalKnown = false, phase = ""))) }; persist()
                    if (code in setOf("NO_SPACE", "YOUTUBE_AUTH_REQUIRED")) { update { it.copy(paused = true) }; persist(); break }
                }
            }
        } finally { active = null; activeId = null }
    }
    private suspend fun resetCancelled(id: String) {
        store.delete(id)
        update { s -> s.copy(entries = s.entries + (id to PreparedEntry(id))) }
        withContext(NonCancellable) { persist() }
    }
    private suspend fun evictUnreferenced(): Int {
        val s = snapshot(); val old = s.entries.keys - s.plan.toSet()
        old.forEach(store::delete)
        if (old.isNotEmpty()) { update { it.copy(entries = it.entries - old) }; persist() }
        return old.size
    }
    suspend fun localOrWait(id: String): ResolvedVideo? {
        while (true) {
            val version = revision.value
            val s = snapshot()
            if (!s.enabled) return null
            val e = s.entries[id]
            if (id in s.deleting) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.READY) {
                if (!store.verify(e)) {
                    update { it.copy(entries = it.entries + (id to e.copy(status = PreparationStatus.ERROR, error = "FILE_DAMAGED"))) }; persist()
                    throw failure("FILE_DAMAGED")
                }
                fun spec(t: LocalTrack) = StreamSpec(File(store.directory, t.name).toURI().toString(), if (t.name.contains(".audio.")) "audio/mp4" else "video/mp4", emptyMap())
                return ResolvedVideo(id, e.title, e.duration, spec(e.tracks[0]), e.tracks.getOrNull(1)?.let(::spec), "Локальный файл, полная подготовка, H.264/AAC")
            }
            if (e?.status == PreparationStatus.DELETED) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.ERROR || s.storageError) throw failure(e?.error ?: "STORAGE_ERROR")
            // No extractor/network fallback in prepared mode. STOP/NEXT cancel this suspension.
            revision.first { it != version }
        }
    }
    private fun failure(code: String) = YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.UNAVAILABLE, diagnostic = code))
}
internal class PreparedYouTubeSource(private val library: PreparedLibrary, private val streaming: YouTubeSource) : YouTubeSource {
    override suspend fun resolve(videoId: String): ResolvedVideo = library.localOrWait(videoId) ?: streaming.resolve(videoId)
}
