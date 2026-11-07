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
    limit: Long = LIBRARY_LIMIT,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
    val budget: MediaBudget = MediaBudget(store.directory, limit, store.storage),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelay: (Int) -> Long = { attempt -> (5_000L * (1L shl (attempt - 1).coerceIn(0,6))).coerceAtMost(300_000L) },
) {
    private val internalLimit=limit
    val limit:Long get() = if(store.storage.selectedId()==INTERNAL_STORAGE) internalLimit else store.storage.limit(store.storage.selectedId())
    internal val storage get() = store.storage
    private val lock = Any()
    private val disk = Mutex()
    private val worker = Mutex()
    private val revision = MutableStateFlow(0L)
    private var state = store.read()
    private val importing = mutableSetOf<String>()
    private val streamingReservations = mutableSetOf<String>()
    private var storageBusy = false
    private var storageCleaning = false
    @Volatile internal var streaming:dev.siyovush.parentcast.tv.streaming.StreamingLibrary?=null
        private set
    @Volatile private var storageJob: Job? = null
    @Volatile private var migrationCancelRequested=false
    @Volatile private var active: Job? = null
    @Volatile private var activeId: String? = null
    @Volatile private var queueReferences: Set<String>? = null
    private var recoveryScheduled=false
    init { if(!File(store.directory,"streaming-plans.json").isFile)resumeDeletionRecovery() }
    private fun resumeDeletionRecovery() {
        val needed=synchronized(lock){if(recoveryScheduled || state.deleting.isEmpty())false else {recoveryScheduled=true;true}}
        if(needed)scope.launch { finishDeletion() }
    }

    fun storageStats(): Pair<Long, Long> = store.usedBytes() to store.storage.volume(store.storage.selectedId()).freeBytes
    internal fun storageAvailability()=store.storage.availability()
    internal fun storageStats(view:StorageAvailability):Pair<Long,Long> {
        val id=store.storage.selectedId();val volume=view.volumes[id]
        val used=volume?.directory?.takeIf { id in view.available }?.let { budget.usedBytes(it) } ?: 0
        return used to (volume?.freeBytes ?: 0)
    }
    internal fun storageLimit(view:StorageAvailability):Long {
        val id=store.storage.selectedId()
        return if(id==INTERNAL_STORAGE)internalLimit else view.volumes[id]?.let(store.storage::limit) ?: 0
    }
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
        // The preparation plan is a priority list, not the complete programme/library.
        queueReferences = queue.items.map { it.videoId }.toSet()
        val target = snapshot().targetGroupId?.let { id -> queue.groups.find { it.id == id } }
        if (snapshot().targetGroupId != null && target == null) { update { it.copy(targetGroupId = null) }; persistLater() }
        val selected = if(snapshot().explicitSelection) snapshot().plan else target?.videoIds ?: queue.remainingItems().map { it.videoId }
        val urgent = if(queue.intent == dev.siyovush.parentcast.tv.queue.PlaybackIntent.PLAY) {
            if(queue.activeGroup != null) queue.remainingItems().map { it.videoId } else listOfNotNull(queue.currentItem?.videoId)
        } else emptyList()
        val online = queue.items.filterNot { it.local }.map { it.videoId }.toSet()
        schedule(((if(snapshot().explicitSelection) urgent.filter { it in selected } else urgent) + selected).distinct().filter { it in online })
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
    suspend fun configure(enabled: Boolean, ids: List<String>, targetGroupId: String? = null, storageId:String?=null, storageVersion:Long?=null, explicitSelection:Boolean=false) {
        require(ids.size <= 10_000 && ids.all(VIDEO_ID::matches))
        check(!snapshot().storageError)
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        synchronized(lock) {
        check(!storageBusy && ids.none { it in streamingReservations }) { "BUSY" }
        if(enabled && storageId!=null) store.storage.claim(ids.filter { snapshot().entries[it]?.status!=PreparationStatus.READY },storageId,storageVersion)
        if (!enabled) active?.cancel()
        update { s -> s.copy(enabled = enabled, paused = false, plan = ids, targetGroupId = targetGroupId, explicitSelection=explicitSelection,
            entries = (s.entries + ids.distinct().filterNot { it in s.entries }.associateWith { PreparedEntry(it) }).mapValues { (id, e) ->
                when {
                    enabled && id in ids && e.status == PreparationStatus.DELETED -> PreparedEntry(id, title = e.title)
                    enabled && id in ids && e.status in setOf(PreparationStatus.ERROR, PreparationStatus.RETRY_WAIT) ->
                        e.copy(status = PreparationStatus.WAITING, error = "", attempts = 0, retryAtMs = 0, phase = "WAITING")
                    else -> e
                }
            }) }
        }
        persist()
        preemptLater(snapshot())
        if (enabled) wakeService()
    }
    suspend fun pause() {
        update { it.copy(paused = true) }; active?.cancel(); persist()
    }
    suspend fun retry() {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        check(synchronized(lock) { !storageBusy }) { "BUSY" }
        update { s -> check(!storageBusy) { "BUSY" };s.copy(paused = false, entries = s.entries.mapValues { (_, e) ->
            if (!e.local && e.status in setOf(PreparationStatus.ERROR, PreparationStatus.RETRY_WAIT)) e.copy(status = PreparationStatus.WAITING, error = "", attempts = 0, retryAtMs = 0) else e
        }) }; persist(); if (snapshot().enabled) wakeService()
    }
    suspend fun deleteMedia(id: String? = null) {
        check(snapshot().deleting.isEmpty()) { "BUSY" }
        require(id == null || MEDIA_ID.matches(id))
        check(synchronized(lock) { id !in importing && !storageBusy && (if(id==null)streamingReservations.isEmpty() else id !in streamingReservations) }) { "BUSY" }
        val onlyLocal = id != null && snapshot().entries[id]?.local == true
        val targets = if (id == null) (snapshot().entries.filterValues { !it.local }.keys + (store.mediaIds().filterNot { LOCAL_ID.matches(it) }.toSet() - snapshot().entries.filterValues { it.local }.keys - synchronized(lock) { importing.toSet() })).toList() else listOf(id)
        update { check(!storageBusy && id !in importing && (if(id==null)streamingReservations.isEmpty() else id !in streamingReservations)) { "BUSY" };it.copy(paused = if (onlyLocal) it.paused else true, deleting = targets, operationError = "") }
        if (!onlyLocal) active?.cancel()
        streaming?.block(targets)
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
            streaming?.delete(targets)
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
        var admitted=false
        try {
        synchronized(lock) { check(!storageBusy) { "BUSY" };storageBusy=true;admitted=true }
        val references = checkNotNull(queueReferences) { "BUSY" }
        val removable = snapshot().let { it.entries.filterValues { e -> !e.local }.keys - it.plan.toSet() - references }
        streaming?.delete(removable)
        removable.forEach(store::delete)
        update { it.copy(entries = it.entries - removable) }; persist(); return removable.size
        } finally { if(admitted)synchronized(lock) { storageBusy=false };worker.unlock() }
    }
    fun hasPending(): Boolean = !synchronized(lock) { storageBusy } && snapshot().let { s -> s.enabled && !s.paused && !s.storageError && s.deleting.isEmpty() && s.plan.any { s.entries[it]?.status in setOf(PreparationStatus.WAITING, PreparationStatus.RETRY_WAIT) } }
    suspend fun runWorker() = worker.withLock {
        active = currentCoroutineContext()[Job]
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val s = snapshot()
                if (!s.enabled || s.paused || s.storageError || s.deleting.isNotEmpty() || synchronized(lock) { storageBusy }) break
                // Finish/recover the earliest pending programme item before starting a later large file.
                val firstPending=s.plan.firstOrNull { id -> id !in synchronized(lock) { streamingReservations.toSet() } && s.entries[id]?.let { !it.local && it.status in setOf(PreparationStatus.WAITING,PreparationStatus.RETRY_WAIT) }==true }
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
                    store.claim(id)
                    if(firstUnprepared(snapshot()) != id) throw CancellationException("Programme priority changed")
                    val shared=streaming?.takeIf { it.isEnabled(id) }
                    if(shared!=null) {
                        val (resolved,tracks)=shared.complete(id) { bytes,total,phase -> update { current ->
                            val old=current.entries.getValue(id);current.copy(entries=current.entries+(id to old.copy(bytes=bytes,total=total,totalKnown=true,phase=phase)))
                        } }
                        tracks.forEachIndexed { index,t -> validate(store.file(id,t.name),index==1,resolved.durationSeconds) }
                        if(tracks.size==1)validate(store.file(id,tracks[0].name),true,resolved.durationSeconds)
                        currentCoroutineContext().ensureActive()
                        update { it.copy(entries=it.entries+(id to PreparedEntry(id,PreparationStatus.READY,resolved.title,resolved.durationSeconds,tracks.sumOf { t->t.bytes },tracks.sumOf { t->t.bytes },tracks=tracks))) }
                        persist();shared.onReady(id);continue
                    }
                    val resolved = source.resolve(id)
                    check(resolved.videoId == id && resolved.durationSeconds > 0) { "UNSUPPORTED" }
                    val tracks = mutableListOf<LocalTrack>()
                    val streams = listOfNotNull(resolved.video, resolved.audio)
                    // Expired links are only held in memory for this attempt; never persisted.
                    for ((index, spec) in streams.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (id !in snapshot().plan) throw CancellationException("Queue item removed")
                        val target = store.track(id, index == 1)
                        val retained = if (target.exists()) target.length() else File(target.path + ".part").length()
                        store.storage.rootFor(id,true)
                        val itemLimit=if(store.storage.placement(id)==INTERNAL_STORAGE)internalLimit else store.storage.limit(store.storage.placement(id))
                        val allowance = itemLimit - store.usedBytesFor(id) + retained
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
                        }.copy(name=store.trackName(id,index==1))
                        update { s1 -> val old = s1.entries.getValue(id)
                            s1.copy(entries = s1.entries + (id to old.copy(phase = "VERIFY"))) }
                        validate(store.file(id, track.name), index == 1, resolved.durationSeconds)
                        if (index == 0 && resolved.audio == null) validate(store.file(id, track.name), true, resolved.durationSeconds)
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
                        error is StorageUnavailable -> error.code
                        error is dev.siyovush.parentcast.tv.streaming.RangeFailure && error.reason in setOf("NO_SPACE","REPRESENTATION_CHANGED","RANGE_UNSUPPORTED","RANGE_IDENTITY_UNCONFIRMED","STORAGE_ERROR","UNAVAILABLE","UNSUPPORTED","YOUTUBE_AUTH_REQUIRED") -> error.reason
                        !store.storage.available(id) -> "STORAGE_UNAVAILABLE"
                        !store.storage.volume(store.storage.placement(id)).writable -> "STORAGE_READ_ONLY"
                        error is YouTubeSourceException && error.failure.stopRequested -> "YOUTUBE_AUTH_REQUIRED"
                        error.message == "NO_SPACE" -> "NO_SPACE"
                        error is MediaStorageFailure -> "STORAGE_ERROR"
                        error is YouTubeSourceException && error.failure.kind == PlaybackErrorKind.UNAVAILABLE -> "UNAVAILABLE"
                        error.message == "UNSUPPORTED" || (error is YouTubeSourceException && error.failure.kind == PlaybackErrorKind.UNSUPPORTED) -> "UNSUPPORTED"
                        else -> "DOWNLOAD_FAILED"
                    }
                    val transient = code == "DOWNLOAD_FAILED" && (error is java.io.IOException || error is YouTubeSourceException)
                    if (transient) {
                        update { s1 -> val old = s1.entries.getValue(id); val attempt = (old.attempts + 1).coerceAtMost(1_000_000)
                            val delay = maxOf(retryDelay(attempt), (error as? RetryableTransfer)?.retryAfterMs ?: 0).coerceIn(1,600_000)
                            s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.RETRY_WAIT, bytes = store.retainedBytes(id), phase = "RETRY", error = "NETWORK_RETRY", attempts = attempt, retryAtMs = now() + delay))) }
                    } else {
                        if (code in setOf("UNSUPPORTED","UNAVAILABLE") || (code=="DOWNLOAD_FAILED" && !transient)) { streaming?.invalidateMaterialized(id);runCatching { store.discardDownload(id) } }
                        update { s1 -> val old = s1.entries.getValue(id)
                            s1.copy(entries = s1.entries + (id to old.copy(status = PreparationStatus.ERROR, error = code, bytes = store.retainedBytes(id), tracks = emptyList(), phase = ""))) }
                    }
                    persist()
                    if (code in setOf("NO_SPACE", "YOUTUBE_AUTH_REQUIRED", "STORAGE_ERROR", "STORAGE_UNAVAILABLE", "STORAGE_READ_ONLY", "STORAGE_NOT_FOUND", "STORAGE_CATALOGUE_ERROR")) { update { it.copy(paused = true) }; persist(); break }
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
    suspend fun localOrWait(id: String, own: Boolean = false, preparedOnly: Boolean = false): ResolvedVideo? {
        while (true) {
            if(synchronized(lock) { storageCleaning })throw failure("STORAGE_BUSY")
            val version = revision.value
            val s = snapshot()
            if (!s.enabled && !own && !preparedOnly) return null
            val e = s.entries[id]
            if (preparedOnly && !s.enabled && e?.status != PreparationStatus.READY) throw failure("PREPARATION_REQUIRED")
            if (own && e?.local != true) throw failure("LOCAL_FILE_NOT_FOUND")
            if (id in s.deleting) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.READY) {
                if(!store.storage.available(id)) throw failure("STORAGE_UNAVAILABLE")
                val context=currentCoroutineContext()
                val verified=try { store.verify(e) { context.ensureActive() } } catch(_:StorageUnavailable) { throw failure("STORAGE_UNAVAILABLE") }
                if (!verified) {
                    if(!store.storage.available(id)) throw failure("STORAGE_UNAVAILABLE")
                    update { it.copy(entries = it.entries + (id to e.copy(status = PreparationStatus.ERROR, error = "FILE_DAMAGED"))) }; persist()
                    throw failure("FILE_DAMAGED")
                }
                fun spec(t: LocalTrack) = StreamSpec(store.file(id, t.name).toURI().toString(), if (t.name.contains(".audio.")) "audio/mp4" else e.mime, emptyMap())
                return ResolvedVideo(id, e.title, e.duration, spec(e.tracks[0]), e.tracks.getOrNull(1)?.let(::spec), if (own) "Своё видео, офлайн" else "Локальный файл, полная подготовка, H.264/AAC")
            }
            if (e?.status == PreparationStatus.DELETED) throw failure("FILE_DELETED")
            if (e?.status == PreparationStatus.ERROR || s.storageError) throw failure(e?.error ?: "STORAGE_ERROR")
            // No extractor/network fallback in prepared mode. STOP/NEXT cancel this suspension.
            revision.first { it != version }
        }
    }
    internal suspend fun importLocal(id: String, title: String, length: Long, input: InputStream, validateLocal: (File) -> LocalMediaInfo): PreparedEntry {
        require(LOCAL_ID.matches(id) && title.isNotBlank() && title.length <= 200 && length in 1..EXTERNAL_LIBRARY_LIMIT)
        check(!snapshot().storageError && snapshot().deleting.isEmpty()) { "BUSY" }
        check(synchronized(lock) { if(storageBusy)error("BUSY"); if (id in state.entries || id in importing) false else importing.add(id) }) { "ID_EXISTS" }
        var target:File?=null;var partial:File?=null
        try {
            store.claim(id);require(length<=if(store.storage.placement(id)==INTERNAL_STORAGE)internalLimit else store.storage.limit(store.storage.placement(id)))
            target=store.track(id);partial=PreparedStorage.safeFile(target.parentFile!!,target.name+".part")
            var copied = 0L
            FileOutputStream(partial).use { out ->
                val bytes = ByteArray(128 * 1024)
                while (copied < length) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes, 0, minOf(bytes.size.toLong(), length - copied).toInt())
                    check(count > 0) { "UPLOAD_INCOMPLETE" }
                    budget.write(out,bytes,count,target); copied += count
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
                tracks = listOf(LocalTrack(store.trackName(id),length,checksum)), local = true, mime = info.mime, totalKnown = true)
            update { it.copy(entries = it.entries + (id to entry)) }; persist()
            return entry
        } catch (error: Throwable) {
            partial?.delete(); target?.delete()
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
        return runCatching { store.track(id).takeIf { it.isFile } }.getOrNull()
    }
    internal fun mediaDuration(id: String): Long? = snapshot().entries[id]?.duration?.takeIf { it > 0 }
    internal fun mediaTitle(id: String): String = snapshot().entries[id]?.title.orEmpty()
    internal fun localEntries(): List<PreparedEntry> = snapshot().entries.values.filter { it.local && it.status == PreparationStatus.READY && it.id !in snapshot().deleting }
    internal fun mediaStorageId(id:String)=store.storage.placement(id)
    internal fun streamingStore()=store
    internal fun installStreaming(value:dev.siyovush.parentcast.tv.streaming.StreamingLibrary){
        streaming=value
        resumeDeletionRecovery()
    }
    internal suspend fun reserveStreaming(id:String,storageId:String,storageVersion:Long) {
        require(VIDEO_ID.matches(id))
        synchronized(lock) { check(!storageBusy && state.deleting.isEmpty() && !state.storageError && id !in streamingReservations) { "BUSY" };streamingReservations.add(id) }
        try {
            if(activeId==id){active?.cancel();worker.withLock { } }
            synchronized(lock) { check(!storageBusy) { "BUSY" };store.storage.claim(listOf(id),storageId,storageVersion);check(store.storage.placement(id)==storageId) { "STORAGE_CHANGED" } }
        } catch(error:Throwable){releaseStreaming(id);throw error}
    }
    internal fun releaseStreaming(id:String){synchronized(lock){streamingReservations.remove(id)}}
    internal suspend fun enableStreaming(id:String) {
        synchronized(lock) { check(!storageBusy && state.deleting.isEmpty()) { "BUSY" }
            update { s -> val old=s.entries[id] ?: PreparedEntry(id)
                // A single-video action never revives an old paused/all-queue preparation plan.
                val previous=if(s.enabled && !s.paused)s.plan else emptyList()
                s.copy(enabled=true,paused=false,explicitSelection=true,targetGroupId=null,plan=(listOf(id)+previous).distinct(),entries=s.entries+(id to if(old.status==PreparationStatus.READY)old else old.copy(status=PreparationStatus.WAITING,error="",retryAtMs=0))) }
        };persist();releaseStreaming(id);wakeService()
    }
    internal fun reserveImport(id:String) { require(LOCAL_ID.matches(id));synchronized(lock) { check(!storageBusy) { "BUSY" };store.claim(id) } }
    internal fun storageAvailable(id:String,view:StorageAvailability?=null)=if(view==null)store.storage.available(id) else store.storage.placement(id) in view.available
    internal fun mediaReadiness(id:String,view:StorageAvailability?=null):String = snapshot().let { s -> when {
        synchronized(lock) { storageCleaning } -> "STORAGE_BUSY"
        id in s.deleting -> "DELETING"
        s.entries[id]?.status==PreparationStatus.READY && !storageAvailable(id,view) -> "STORAGE_UNAVAILABLE"
        else -> s.entries[id]?.status?.name ?: "WAITING"
    } }
    internal fun allowsPlaybackFile(id:String,file:File):Boolean = runCatching {
        val canonical=file.canonicalFile
        val e=snapshot().entries[id]
        e!=null && e.status==PreparationStatus.READY && id !in snapshot().deleting && (e.tracks.any { t ->
            runCatching { store.file(e.id,t.name).let { it.isFile && it.canonicalFile==canonical } }.getOrDefault(false)
        } || store.storage.retained().filter { it.id==id }.any { o -> o.tracks.any { t ->
            runCatching { store.fileAt(o.id,t.name,o.storageId).let { it.isFile && it.canonicalFile==canonical } }.getOrDefault(false)
        } })
    }.getOrDefault(false)

    internal suspend fun selectStorage(id:String,version:Long,command:StorageCommand) {
        synchronized(lock) { check(!storageBusy) { "BUSY" };store.storage.select(id,version,command) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun migrateStorage(target:String,version:Long,command:StorageCommand) {
        synchronized(lock) { check(!storageBusy && importing.isEmpty() && streamingReservations.isEmpty() && state.deleting.isEmpty() && !state.storageError) { "BUSY" };storageBusy=true;migrationCancelRequested=false }
        var admitted=false
        try {
            val candidates=snapshot().entries.values.filter { it.status==PreparationStatus.READY && store.storage.placement(it.id)!=target }
            store.storage.beginMigration(target,candidates,version,command)
            admitted=true
            update { it.copy(paused=true) };active?.cancel();persist()
            storageJob=scope.launch(start=CoroutineStart.ATOMIC) {
                try {
                    worker.withLock {
                        checkMigrationCancellation()
                        var completedBytes=0L
                        for(entry in candidates) {
                            checkMigrationCancellation()
                            val sourceId=store.storage.placement(entry.id)
                            val context=currentCoroutineContext()
                            for(track in entry.tracks) {
                                val sourceFile=store.fileAt(entry.id,track.name,sourceId)
                                check(sourceFile.isFile && sourceFile.length()==track.bytes && sha256(sourceFile) { context.ensureActive() }==track.sha256) { "SOURCE_CHANGED" }
                                val destination=store.fileAt(entry.id,track.name,target,true)
                                if(destination.exists()) {
                                    check(destination.length()==track.bytes && sha256(destination) { context.ensureActive() }==track.sha256) { "DESTINATION_CONFLICT" }
                                } else {
                                    store.storage.stage(StagedMediaCopy(entry.id,target,track))
                                    val temporary=PreparedStorage.safeFile(destination.parentFile!!,destination.name+".copying")
                                    // An interrupted attempt's own temporary copy is replaceable; originals never are.
                                    if(temporary.exists())check(temporary.delete()) { "TRANSFER_FAILED" }
                                    check(store.storage.usedBytes(target)+track.bytes<=store.storage.limit(target) && store.storage.volume(target).freeBytes-track.bytes>=FREE_RESERVE) { "NO_SPACE" }
                                    var copied=0L
                                    sourceFile.inputStream().use { input -> FileOutputStream(temporary).use { output ->
                                        val buffer=ByteArray(4*1024*1024);var lastReport=0L
                                        while(true) {
                                            checkMigrationCancellation();val count=input.read(buffer);if(count<0)break
                                            check(copied+count<=track.bytes) { "SOURCE_CHANGED" }
                                            budget.write(output,buffer,count,destination);copied+=count
                                            val now=System.nanoTime();if(now-lastReport>500_000_000L){store.storage.progress("COPYING",completedBytes+copied);lastReport=now}
                                        }
                                        output.fd.sync()
                                    } }
                                    check(copied==track.bytes) { "SOURCE_CHANGED" }
                                    store.storage.progress("VERIFYING",completedBytes+copied)
                                    check(sha256(temporary) { context.ensureActive() }==track.sha256) { "COPY_VERIFY_FAILED" }
                                    context.ensureActive();store.storage.root(target,true)
                                    check(temporary.renameTo(destination)) { "TRANSFER_FAILED" }
                                }
                                completedBytes+=track.bytes
                            }
                            checkMigrationCancellation()
                            check(entry.tracks.all { t -> val file=store.fileAt(entry.id,t.name,target);file.length()==t.bytes && sha256(file) { context.ensureActive() }==t.sha256 }) { "COPY_VERIFY_FAILED" }
                            store.storage.switchCopy(entry,sourceId,target,completedBytes)
                            streaming?.moved(entry.id,target)
                            update { it }
                        }
                        checkMigrationCancellation();store.storage.finish("COMPLETE")
                    }
                } catch(_:CancellationException) { withContext(NonCancellable) { runCatching { store.discardStaged(targetId=target) };runCatching { store.storage.finish("CANCELLED") } } }
                  catch(error:Exception) { runCatching { store.discardStaged(targetId=target) };runCatching { store.storage.finish("ERROR",storageFailureCode(error)) } }
                finally { synchronized(lock) { storageJob=null;storageBusy=false };update { it } }
            }
        } catch(error:Exception) { if(admitted)runCatching { store.storage.finish("ERROR",storageFailureCode(error)) };synchronized(lock) { storageBusy=false };throw error }
    }
    internal fun cancelStorageMigration(version:Long,command:StorageCommand) {
        synchronized(lock) { store.storage.cancel(version,command);migrationCancelRequested=true;storageJob?.cancel() }
    }
    internal suspend fun cleanStorageOriginals(version:Long,command:StorageCommand,guard:()->Boolean) {
        synchronized(lock) { check(!storageBusy && importing.isEmpty() && streamingReservations.isEmpty() && state.deleting.isEmpty()) { "BUSY" };storageBusy=true;storageCleaning=true }
        var admitted=false
        try {
            check(guard()) { "PLAYBACK_CHANGED" }
            store.storage.beginCleanup(version,command)
            admitted=true
            update { it.copy(paused=true) };active?.cancel();persist()
            storageJob=scope.launch {
                try { worker.withLock {
                    for(original in store.storage.retained()) {
                        currentCoroutineContext().ensureActive();check(guard()) { "PLAYBACK_CHANGED" }
                        // A reverse migration may already have made this copy active again.
                        if(store.storage.placement(original.id)==original.storageId) { store.storage.removeOriginal(original);continue }
                        val context=currentCoroutineContext()
                        val activeEntry=snapshot().entries[original.id]
                        check(store.storage.durablePlacement(original.id)==store.storage.placement(original.id)) { "STORAGE_CHANGED" }
                        check(activeEntry?.status==PreparationStatus.READY && activeEntry.tracks==original.tracks && store.verify(activeEntry) { context.ensureActive() }) { "COPY_VERIFY_FAILED" }
                        val originals=original.tracks.map { t -> store.fileAt(original.id,t.name,original.storageId,true).also { f ->
                            check(!f.exists() || f.length()==t.bytes && sha256(f) { context.ensureActive() }==t.sha256) { "SOURCE_CHANGED" }
                        } }
                        check(guard()) { "PLAYBACK_CHANGED" }
                        originals.forEach { f -> check(!f.exists() || f.delete()) { "DELETE_FAILED" } }
                        // Resume sidecars are app-owned metadata for the now-deleted original files.
                        PreparedStorage.files(store.storage.root(original.storageId,true),original.id,original.storageId!=INTERNAL_STORAGE)
                            .filter { !it.name.endsWith(".mp4") }.forEach { check(!it.exists() || it.delete()) { "DELETE_FAILED" } }
                        store.storage.removeOriginal(original)
                    }
                    check(guard()) { "PLAYBACK_CHANGED" };store.discardStaged()
                    store.storage.finish("COMPLETE")
                } }catch(error:Exception) { runCatching { store.storage.finish("ERROR",storageFailureCode(error)) } }
                finally { synchronized(lock) { storageJob=null;storageBusy=false;storageCleaning=false };update { it } }
            }
        }catch(error:Exception) { if(admitted)runCatching { store.storage.finish("ERROR",storageFailureCode(error)) };synchronized(lock) { storageBusy=false;storageCleaning=false };throw error }
    }
    private suspend fun checkMigrationCancellation() { currentCoroutineContext().ensureActive();if(migrationCancelRequested)throw CancellationException("Migration cancelled") }
    private fun storageFailureCode(error:Exception):String = if(error is StorageUnavailable)error.code else error.message?.takeIf { it in setOf("NO_SPACE","SOURCE_CHANGED","DESTINATION_CONFLICT","COPY_VERIFY_FAILED","TRANSFER_FAILED","DELETE_FAILED","PLAYBACK_CHANGED") } ?: "STORAGE_ERROR"

    private fun failure(code: String) = YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.UNAVAILABLE, diagnostic = code))
}
internal class PreparedYouTubeSource(private val library: PreparedLibrary, private val streaming: YouTubeSource) : YouTubeSource {
    override suspend fun resolve(videoId: String): ResolvedVideo = library.localOrWait(videoId, preparedOnly = dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVk(videoId)) ?: streaming.resolve(videoId)
}

internal data class LocalMediaInfo(val durationSeconds: Long, val mime: String)
