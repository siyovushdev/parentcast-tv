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
import java.io.InputStream
import java.io.FileOutputStream

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
    val budget: MediaBudget = MediaBudget(store.directory, limit),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelay: (Int) -> Long = { attempt -> (5_000L * (1L shl (attempt - 1).coerceIn(0,6))).coerceAtMost(300_000L) },
) {
    private val lock = Any()
    private val disk = Mutex()
    private val worker = Mutex()
    private val revision = MutableStateFlow(0L)
    private var state = store.read()
    private val importing = mutableSetOf<String>()
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
    fun networkAvailable() {
        val current=snapshot()
        if(!current.enabled || current.paused || current.storageError || current.entries.values.none { it.status==PreparationStatus.RETRY_WAIT }) return
        update { s -> s.copy(entries=s.entries.mapValues { (_,e) -> if(e.status==PreparationStatus.RETRY_WAIT) e.copy(retryAtMs=now()) else e }) }
        persistLater();wakeService()
    }
    fun syncQueue(queue: dev.siyovush.parentcast.tv.queue.QueueState) {
        val target = snapshot().targetGroupId?.let { id -> queue.groups.find { it.id == id } }
        if (snapshot().targetGroupId != null && target == null) { update { it.copy(targetGroupId = null) }; persistLater() }
        val selected = target?.videoIds ?: queue.remainingItems().map { it.videoId }
        val urgent = if(queue.intent == dev.siyovush.parentcast.tv.queue.PlaybackIntent.PLAY) {
            if(queue.activeGroup != null) queue.remainingItems().map { it.videoId } else listOfNotNull(queue.currentItem?.videoId)
        } else emptyList()
        val online = queue.items.filterNot { it.local }.map { it.videoId }.toSet()
        schedule((urgent + selected).distinct().filter { it in online })
    }
    private fun firstUnprepared(s: PreparationState) = s.plan.firstOrNull { id -> s.entries[id]?.let {
        !it.local && it.status in setOf(PreparationStatus.WAITING,PreparationStatus.RETRY_WAIT,PreparationStatus.DOWNLOADING)
    } == true }
    private fun preemptLater(s: PreparationState) {
        val id = activeId ?: return
        if(s.entries[id]?.status == PreparationStatus.DOWNLOADING && firstUnprepared(s) != id) active?.cancel()
    }
    fun schedule(ids: List<String>) {
        require(ids.size <= 10_000 && ids.all(VIDEO_ID::matches))
        if (snapshot().plan == ids) { preemptLater(snapshot()); return }
        update { s -> s.copy(plan = ids, entries = s.entries + ids.distinct().filterNot { it in s.entries }.associateWith { PreparedEntry(it) }) }
        persistLater()
        preemptLater(snapshot())
        val current = snapshot()
        if (current.enabled && !current.paused) wakeService()
    }
    suspend fun configure(enabled: Boolean, ids: List<String>, targetGroupId: String? = null) {
        require(ids.size <= 10_000 && ids.all(VIDEO_ID::matches))
        check(!snapshot().storageError)
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        if (!enabled) active?.cancel()
        update { s -> s.copy(enabled = enabled, paused = false, plan = ids, targetGroupId = targetGroupId,
            entries = (s.entries + ids.distinct().filterNot { it in s.entries }.associateWith { PreparedEntry(it) }).mapValues { (id, e) ->
                if (enabled && id in ids && e.status == PreparationStatus.DELETED) PreparedEntry(id, title = e.title) else e
            }) }
        persist()
        preemptLater(snapshot())
        if (enabled) wakeService()
    }
    suspend fun pause() {
        update { it.copy(paused = true) }; active?.cancel(); persist()
    }
    suspend fun retry() {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        update { s -> s.copy(paused = false, entries = s.entries.mapValues { (_, e) ->
            if (!e.local && e.status in setOf(PreparationStatus.ERROR, PreparationStatus.RETRY_WAIT)) e.copy(status = PreparationStatus.WAITING, error = "", attempts = 0, retryAtMs = 0) else e
        }) }; persist(); if (snapshot().enabled) wakeService()
    }
    suspend fun deleteMedia(id: String? = null) {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        require(id == null || MEDIA_ID.matches(id))
        check(synchronized(lock) { id !in importing }) { "BUSY" }
        val onlyLocal = id != null && snapshot().entries[id]?.local == true
        val targets = if (id == null) (snapshot().entries.filterValues { !it.local }.keys + (store.mediaIds().filterNot { LOCAL_ID.matches(it) }.toSet() - snapshot().entries.filterValues { it.local }.keys - synchronized(lock) { importing.toSet() })).toList() else listOf(id)
        update { it.copy(paused = if (onlyLocal) it.paused else true, deleting = targets, operationError = "") }
        if (!onlyLocal) active?.cancel()
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
    private suspend fun finishDeletion() {
        val targets = snapshot().deleting
        val deletion: suspend () -> Unit = {
        try {
            targets.forEach(store::deleteChecked)
            update { s -> s.copy(deleting = emptyList(), operationError = "", entries = s.entries.mapValues { (id, e) ->
                if (id in targets) PreparedEntry(id, PreparationStatus.DELETED, title = e.title, phase = "DELETED", local = e.local, mime = e.mime) else e
            }) }
            persist()
        } catch (_: Exception) {
            update { it.copy(operationError = "DELETE_FAILED", paused = true) }
            runCatching { persist() }
        }
        }
        if (targets.all { snapshot().entries[it]?.local == true }) deletion() else worker.withLock { deletion() }
    }
    suspend fun cleanup(): Int {
        check(worker.tryLock()) { "BUSY" }
        try {
        val removable = snapshot().let { it.entries.filterValues { e -> !e.local }.keys - it.plan.toSet() }
        removable.forEach(store::delete)
        update { it.copy(entries = it.entries - removable) }; persist(); return removable.size
        } finally { worker.unlock() }
    }
    fun hasPending(): Boolean = snapshot().let { s -> s.enabled && !s.paused && !s.storageError && s.deleting.isEmpty() && s.plan.any { s.entries[it]?.status in setOf(PreparationStatus.WAITING, PreparationStatus.RETRY_WAIT) } }
    suspend fun runWorker() = worker.withLock {
        active = currentCoroutineContext()[Job]
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val s = snapshot()
                if (!s.enabled || s.paused || s.storageError || s.deleting.isNotEmpty()) break
                // Finish/recover the earliest pending programme item before starting a later large file.
                val firstPending=s.plan.firstOrNull { id -> s.entries[id]?.let { !it.local && it.status in setOf(PreparationStatus.WAITING,PreparationStatus.RETRY_WAIT) }==true }
                val id=firstPending?.takeIf { s.entries.getValue(it).let { e -> e.status==PreparationStatus.WAITING || e.retryAtMs<=now() } }
                if (id == null) {
                    val next = firstPending?.let { s.entries.getValue(it).retryAtMs } ?: break
                    val version = revision.value
                    withTimeoutOrNull((next - now()).coerceIn(1,300_000)) { revision.first { it != version } }
                    continue
                }
                activeId = id
                update { s1 -> val old = s1.entries.getValue(id); s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.DOWNLOADING, phase = "INFO", error = "", retryAtMs = 0))) }; persist()
                try {
                    if(firstUnprepared(snapshot()) != id) throw CancellationException("Programme priority changed")
                    val resolved = source.resolve(id)
                    check(resolved.videoId == id && resolved.durationSeconds > 0) { "UNSUPPORTED" }
                    val tracks = mutableListOf<LocalTrack>()
                    val streams = listOfNotNull(resolved.video, resolved.audio)
                    // Expired links are only held in memory for this attempt; never persisted.
                    for ((index, spec) in streams.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (id !in snapshot().plan) throw CancellationException("Queue item removed")
                        if (store.usedBytes() >= limit) evictUnreferenced()
                        val target = store.track(id, index == 1)
                        val retained = if (target.exists()) target.length() else File(target.path + ".part").length()
                        val allowance = limit - store.usedBytes() + retained
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
                    val code = when {
                        error is YouTubeSourceException && error.failure.stopRequested -> "YOUTUBE_AUTH_REQUIRED"
                        error.message == "NO_SPACE" -> "NO_SPACE"
                        error is MediaStorageFailure -> "STORAGE_ERROR"
                        error is YouTubeSourceException && error.failure.kind == PlaybackErrorKind.UNAVAILABLE -> "UNAVAILABLE"
                        error.message == "UNSUPPORTED" || (error is YouTubeSourceException && error.failure.kind == PlaybackErrorKind.UNSUPPORTED) -> "UNSUPPORTED"
                        else -> "DOWNLOAD_FAILED"
                    }
                    if (code == "NO_SPACE" && evictUnreferenced() > 0) {
                        update { s1 -> s1.copy(entries = s1.entries + (id to PreparedEntry(id))) }; persist()
                        continue // One retry after reclaiming watched/unreferenced files; never evict the remaining plan.
                    }
                    val transient = code == "DOWNLOAD_FAILED" && (error is java.io.IOException || error is YouTubeSourceException)
                    if (transient) {
                        update { s1 -> val old = s1.entries.getValue(id); val attempt = (old.attempts + 1).coerceAtMost(1_000_000)
                            val delay = maxOf(retryDelay(attempt), (error as? RetryableTransfer)?.retryAfterMs ?: 0).coerceIn(1,600_000)
                            s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.RETRY_WAIT, bytes = store.retainedBytes(id), phase = "RETRY", error = "NETWORK_RETRY", attempts = attempt, retryAtMs = now() + delay))) }
                    } else {
                        if (code in setOf("UNSUPPORTED","UNAVAILABLE") || (code=="DOWNLOAD_FAILED" && !transient)) store.delete(id)
                        update { s1 -> val old = s1.entries.getValue(id)
                            s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.ERROR, error = code, bytes = store.retainedBytes(id), tracks = emptyList(), phase = ""))) }
                    }
                    persist()
                    if (code in setOf("NO_SPACE", "YOUTUBE_AUTH_REQUIRED", "STORAGE_ERROR")) { update { it.copy(paused = true) }; persist(); break }
                }
            }
        } finally { active = null; activeId = null }
    }
    private suspend fun resetCancelled(id: String) {
        withContext(NonCancellable) {
            update { s -> val old = s.entries[id] ?: PreparedEntry(id)
                s.copy(entries = s.entries + (id to if(old.status == PreparationStatus.DOWNLOADING) old.copy(status = PreparationStatus.WAITING, bytes = store.retainedBytes(id), phase = "WAITING", retryAtMs = 0) else old)) }
            persist()
        }
    }
    private suspend fun evictUnreferenced(): Int {
        val s = snapshot(); val old = s.entries.filterValues { !it.local }.keys - s.plan.toSet()
        old.forEach(store::delete)
        if (old.isNotEmpty()) { update { it.copy(entries = it.entries - old) }; persist() }
        return old.size
    }
    suspend fun localOrWait(id: String, own: Boolean = false, preparedOnly: Boolean = false): ResolvedVideo? {
        while (true) {
            val version = revision.value
            val s = snapshot()
            if (!s.enabled && !own && !preparedOnly) return null
            val e = s.entries[id]
            if (preparedOnly && !s.enabled && e?.status != PreparationStatus.READY) throw failure("PREPARATION_REQUIRED")
            if (own && e?.local != true) throw failure("LOCAL_FILE_NOT_FOUND")
            if (id in s.deleting) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.READY) {
                val context=currentCoroutineContext()
                if (!store.verify(e) { context.ensureActive() }) {
                    update { it.copy(entries = it.entries + (id to e.copy(status = PreparationStatus.ERROR, error = "FILE_DAMAGED"))) }; persist()
                    throw failure("FILE_DAMAGED")
                }
                fun spec(t: LocalTrack) = StreamSpec(File(store.directory, t.name).toURI().toString(), if (t.name.contains(".audio.")) "audio/mp4" else e.mime, emptyMap())
                return ResolvedVideo(id, e.title, e.duration, spec(e.tracks[0]), e.tracks.getOrNull(1)?.let(::spec), if (own) "Своё видео, офлайн" else "Локальный файл, полная подготовка, H.264/AAC")
            }
            if (e?.status == PreparationStatus.DELETED) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.ERROR || s.storageError) throw failure(e?.error ?: "STORAGE_ERROR")
            // No extractor/network fallback in prepared mode. STOP/NEXT cancel this suspension.
            revision.first { it != version }
        }
    }
    internal suspend fun importLocal(id: String, title: String, length: Long, input: InputStream, validateLocal: (File) -> LocalMediaInfo): PreparedEntry {
        require(LOCAL_ID.matches(id) && title.isNotBlank() && title.length <= 200 && length in 1..limit)
        check(!snapshot().storageError && snapshot().deleting.isEmpty()) { "BUSY" }
        check(synchronized(lock) { if (id in state.entries || id in importing) false else importing.add(id) }) { "ID_EXISTS" }
        val target = store.track(id); val partial = File(target.path + ".part")
        try {
            var copied = 0L
            FileOutputStream(partial).use { out ->
                val bytes = ByteArray(128 * 1024)
                while (copied < length) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes, 0, minOf(bytes.size.toLong(), length - copied).toInt())
                    check(count > 0) { "UPLOAD_INCOMPLETE" }
                    budget.write(out,bytes,count); copied += count
                }
                out.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            check(input.read() == -1) { "UPLOAD_SIZE_MISMATCH" }
            val info = validateLocal(partial)
            check(partial.renameTo(target)) { "IMPORT_FAILED" }
            val context=currentCoroutineContext()
            val checksum=sha256(target) { context.ensureActive() }
            context.ensureActive()
            val entry = PreparedEntry(id, PreparationStatus.READY, title, info.durationSeconds, length, length,
                tracks = listOf(LocalTrack(target.name,length,checksum)), local = true, mime = info.mime, totalKnown = true)
            update { it.copy(entries = it.entries + (id to entry)) }; persist()
            return entry
        } catch (error: Throwable) {
            partial.delete(); target.delete()
            update { it.copy(entries = it.entries - id) }
            withContext(NonCancellable) { runCatching { persist() } }
            throw error
        } finally { synchronized(lock) { importing.remove(id) } }
    }
    internal fun discardIncompleteImport(id: String) {
        require(LOCAL_ID.matches(id))
        if (snapshot().entries[id] == null && synchronized(lock) { id !in importing }) store.delete(id)
    }
    internal fun thumbnailFile(id: String): File? {
        val snapshot = snapshot()
        val entry = snapshot.entries[id] ?: return null
        if (entry.status != PreparationStatus.READY || id in snapshot.deleting) return null
        return store.track(id).takeIf { it.isFile }
    }
    internal fun mediaTitle(id: String): String = snapshot().entries[id]?.title.orEmpty()
    internal fun localEntries(): List<PreparedEntry> = snapshot().entries.values.filter { it.local && it.status == PreparationStatus.READY && it.id !in snapshot().deleting }

    private fun failure(code: String) = YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.UNAVAILABLE, diagnostic = code))
}
internal class PreparedYouTubeSource(private val library: PreparedLibrary, private val streaming: YouTubeSource) : YouTubeSource {
    override suspend fun resolve(videoId: String): ResolvedVideo = library.localOrWait(videoId, preparedOnly = dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVk(videoId)) ?: streaming.resolve(videoId)
}

internal data class LocalMediaInfo(val durationSeconds: Long, val mime: String)
