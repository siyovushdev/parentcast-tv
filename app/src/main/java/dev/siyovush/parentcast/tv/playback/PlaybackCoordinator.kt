package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.queue.QueueGroup
import dev.siyovush.parentcast.tv.queue.cleanVideoTitle
import dev.siyovush.parentcast.tv.queue.BatchAddResult
import dev.siyovush.parentcast.tv.queue.BatchReceipt
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.queue.QueueItem
import dev.siyovush.parentcast.tv.queue.QueueAddition
import dev.siyovush.parentcast.tv.queue.QueueState
import dev.siyovush.parentcast.tv.queue.QueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PlaybackCoordinator(
    private val source: YouTubeSource,
    private val store: QueueStore,
    private val player: PlaybackPlayer,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authorizationGate: () -> Boolean = { true },
    private val autoResumeOnStartup: Boolean = true,
    private val localSource: YouTubeSource? = null,
) : PlaybackPlayer.Listener {
    private var state = QueueState.Empty
    private var committedState = QueueState.Empty
    private var status = PlaybackStatus.IDLE
    private var currentTitle = ""
    private var diagnostics = ""
    private var localPlayback = false
    private var error: PlaybackFailure? = null
    private var storageWarning = false
    private var startupStorageBlocked = false
    private var generation = 0L
    private var extractionJob: Job? = null
    private var preparedItemId: String? = null
    private var refreshAttempted = false
    private var listener: ((CoordinatorSnapshot) -> Unit)? = null
    private var started = false
    private var closed = false
    private var updatedAtMs = System.currentTimeMillis()

    init {
        state = try {
            store.read()
        } catch (error: Exception) {
            storageWarning = true
            QueueState.Empty
        }
        committedState = state
    }

    fun setListener(listener: (CoordinatorSnapshot) -> Unit) {
        this.listener = listener
        emit()
    }

    fun start() {
        if (started || closed) return
        started = true
        player.setListener(this)
        var startupState = StartupPlaybackPolicy.restore(state, autoResumeOnStartup)
        if (startupState != state) {
            try {
                // Commit the safe intent before any extraction can be scheduled.
                startupState = commitState(startupState)
            } catch (_: Exception) {
                state = startupState
                storageWarning = true
                startupStorageBlocked = true
                status = PlaybackStatus.PAUSED
                emit()
                return
            }
            state = startupState
        }
        if (state.finished) {
            status = PlaybackStatus.ENDED
        } else if (state.currentItem != null) {
            status = PlaybackStatus.STOPPED
        }
        emit()
        if (state.currentItem != null && state.intent != PlaybackIntent.STOP && !state.finished) {
            prepareCurrent()
        }
    }

    fun add(videoId: String) {
        if (closed) return
        var updated = state.add(QueueItem(UUID.randomUUID().toString(), videoId))
        if (updated == state) return
        state = updated
        if (status == PlaybackStatus.IDLE) status = PlaybackStatus.STOPPED
        error = null
        persist()
        emit()
    }

    fun batchReplay(commandKey: String, fingerprint: String): BatchAddResult? {
        val receipt = state.batchReceipts.find { it.key == commandKey && System.currentTimeMillis() - it.createdAtMs <= 86_400_000L }
            ?: return null
        return if (receipt.fingerprint == fingerprint) BatchAddResult.REPLAY else BatchAddResult.KEY_REUSED
    }

    fun additionSummary(commandKey: String): QueueAddition? = state.batchReceipts.find { it.key == commandKey }?.addition

    /** One queue transaction; no player calls and no change of playback intent. */
    fun addBatch(videoIds: List<String>, expectedVersion: Long, commandKey: String, requestFingerprint: String? = null, local: Boolean = false, group: QueueGroup? = null, titles: Map<String,String> = emptyMap()): BatchAddResult {
        require(videoIds.size in 1..500 && videoIds.all { if(local) Regex("local_[0-9a-f]{32}").matches(it) else dev.siyovush.parentcast.tv.media.OnlineMediaIds.isOnline(it) })
        require(group == null || group.videoIds.containsAll(videoIds.distinct()))
        require(titles.keys.all { it in videoIds } && titles.values.all { it == cleanVideoTitle(it) })
        require(commandKey.length in 8..300)
        if (closed) return BatchAddResult.STORAGE_ERROR
        val fingerprint = requestFingerprint ?: java.security.MessageDigest.getInstance("SHA-256")
            .digest((expectedVersion.toString() + "|" + videoIds.joinToString(",") + (if (local) "|local" else "") + "|" + group.toString() + "|" + titles.toSortedMap().toString()).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val receipts = state.batchReceipts.filter { now - it.createdAtMs <= 86_400_000L }
        receipts.find { it.key == commandKey }?.let {
            return if (it.fingerprint == fingerprint) BatchAddResult.REPLAY else BatchAddResult.KEY_REUSED
        }
        if (state.version != expectedVersion) return BatchAddResult.CONFLICT
        require(group == null || group.videoIds.all { id -> id in videoIds || state.items.any { it.videoId == id } })
        if (receipts.size >= 1000) return BatchAddResult.RECEIPT_LIMIT
        val seen = state.items.mapTo(mutableSetOf()) { it.videoId }
        val items = videoIds.filter { seen.add(it) }.map { QueueItem(UUID.randomUUID().toString(), it, local, titles[it].orEmpty()) }
        val allItems = (state.items + items).map { item -> titles[item.videoId]?.let { item.copy(title = it) } ?: item }
        val previousGroup=group?.let { incoming -> state.groups.find { it.id==incoming.id } }
        val incomingGroup=group?.let { incoming -> previousGroup?.let { old -> incoming.copy(favorite=old.favorite,coverImageId=old.coverImageId,coverVideoId=old.coverVideoId?.takeIf { it in incoming.videoIds }) } ?: incoming }
        val groups = if (incomingGroup == null) state.groups else if(previousGroup!=null) state.groups.map { if(it.id == incomingGroup.id) incomingGroup else it } else state.groups + incomingGroup
        if (groups.size > 100) return BatchAddResult.RECEIPT_LIMIT
        val addition = QueueAddition(items.size, videoIds.size - items.size, group?.id)
        val changed = allItems != state.items || groups != state.groups
        var updated = state.copy(
            items = allItems,
            groups = groups,
            currentIndex = if (state.items.isEmpty() && items.isNotEmpty()) 0 else state.currentIndex,
            version = state.version + if (changed) 1 else 0,
            batchReceipts = receipts + BatchReceipt(commandKey, fingerprint, now, addition),
        )
        try {
            updated = commitState(updated)
        } catch (_: Exception) {
            storageWarning = true
            emit()
            return BatchAddResult.STORAGE_ERROR
        }
        state = updated
        if (status == PlaybackStatus.IDLE) status = PlaybackStatus.STOPPED
        emit()
        return BatchAddResult.APPLIED
    }

    /** Authenticated programme commands share the queue's atomic receipt and version transaction. */
    fun programmeCommand(command: ProgrammeCommand, expectedVersion: Long, key: String, fingerprint: String): BatchAddResult {
        if(closed)return BatchAddResult.STORAGE_ERROR
        batchReplay(key,fingerprint)?.let { return it }
        if(state.version!=expectedVersion)return BatchAddResult.CONFLICT
        val now=System.currentTimeMillis()
        val receipts=state.batchReceipts.filter { now-it.createdAtMs<=86_400_000L }
        if(receipts.size>=1000)return BatchAddResult.RECEIPT_LIMIT
        val previousCurrent=state.currentItem?.queueItemId
        val updated=state.applyProgramme(command).copy(version=state.version+1,batchReceipts=receipts+BatchReceipt(key,fingerprint,now))
        try { state=commitState(updated, programmeLabel(command), command !is ProgrammeCommand.Undo) }
        catch(_:Exception){storageWarning=true;emit();return BatchAddResult.STORAGE_ERROR}
        if(previousCurrent!=state.currentItem?.queueItemId) {
            invalidateRequest();currentTitle="";diagnostics="";error=null;status=PlaybackStatus.STOPPED
        }
        emit();return BatchAddResult.APPLIED
    }
    private fun programmeLabel(command: ProgrammeCommand): String = when(command) {
        is ProgrammeCommand.ReorderLoose -> "Порядок отдельных видео"
        is ProgrammeCommand.CreateGroup -> "Создание плейлиста"
        is ProgrammeCommand.UpdateGroup -> "Настройки плейлиста"
        is ProgrammeCommand.Membership -> "Состав плейлиста"
        is ProgrammeCommand.RemoveVideos -> "Удаление из программы"
        is ProgrammeCommand.Favorite -> "Избранное"
        else -> "Изменение программы"
    }
    private fun commitState(next: QueueState, label: String = "Изменение программы", undoable: Boolean = true): QueueState {
        val before=committedState.structure()
        val after=next.structure()
        val updated=if(undoable && before!=after)next.copy(undo=ProgrammeUndo("u_"+UUID.randomUUID().toString().replace("-",""),label,before,after.fingerprint())) else next
        store.write(updated)
        committedState=updated
        return updated
    }

    private fun currentNeedsYouTubeSession(): Boolean = state.currentItem?.let {
        !it.local && !dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVk(it.videoId)
    } ?: true

    fun play() {
        if (closed || state.items.isEmpty()) return
        state = state.play()
        error = null
        refreshAttempted = false
        persist()
        if (!authorizationGate() && currentNeedsYouTubeSession()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        val current = state.currentItem ?: return
        if (preparedItemId == current.queueItemId) {
            status = PlaybackStatus.BUFFERING
            player.play()
            emit()
        } else {
            prepareCurrent()
        }
    }

    fun onSessionChanged() {
        if (closed || startupStorageBlocked) return
        if (!authorizationGate() && currentNeedsYouTubeSession()) {
            if (state.intent != PlaybackIntent.STOP && !state.finished) {
                status = PlaybackStatus.WAITING_FOR_SESSION
                emit()
            }
            return
        }
        if (state.intent == PlaybackIntent.STOP || state.finished) return
        if (preparedItemId == null) prepareCurrent()
    }

    fun pauseResume() {
        if (closed || state.currentItem == null) return
        if (state.intent != PlaybackIntent.PLAY) {
            play()
            return
        }
        savePosition()
        state = state.togglePause()
        player.pause()
        status = PlaybackStatus.PAUSED
        persist()
        emit()
    }

    fun next() {
        if (closed || state.items.isEmpty()) return
        state = captureProgress(state).next()
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (state.currentItem != null && !state.finished && state.intent != PlaybackIntent.STOP) {
            prepareCurrent()
        } else {
            invalidateRequest()
            status = if (state.finished) PlaybackStatus.ENDED else PlaybackStatus.STOPPED
            emit()
        }
    }

    fun stop() {
        if (closed) return
        savePosition()
        state = state.stop()
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        invalidateRequest()
        status = PlaybackStatus.STOPPED
        persist()
        emit()
    }

    fun clearAll(expectedVersion: Long): Boolean {
        if (closed || state.version != expectedVersion) return false
        var cleared = QueueState.Empty.copy(version = state.version + 1, batchReceipts = state.batchReceipts, progress=state.progress, favorites=state.favorites, childScreen=ChildScreen(state.childScreen.enabled))
        cleared = commitState(cleared)
        state = cleared
        invalidateRequest()
        currentTitle = ""; diagnostics = ""; error = null
        status = PlaybackStatus.STOPPED
        emit()
        return true
    }

    fun deletePending(queueItemId: String) {
        if (closed) return
        var updated = state.deletePending(queueItemId)
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    fun playNow(videoId: String) {
        if (closed) return
        state = captureProgress(state).playNow(QueueItem(UUID.randomUUID().toString(), videoId))
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (!authorizationGate() && currentNeedsYouTubeSession()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        prepareCurrent()
    }

    /** Explicit parent selection, without inserting another copy into the programme. */
    fun selectQueued(id: String, expectedVersion: Long, positionMs: Long, play: Boolean, key: String, fingerprint: String, groupId: String? = null): BatchAddResult {
        if(closed) return BatchAddResult.STORAGE_ERROR
        batchReplay(key,fingerprint)?.let { return it }
        if(state.version!=expectedVersion) return BatchAddResult.CONFLICT
        val index=state.items.indexOfFirst { it.queueItemId==id }
        require(index>=0 && positionMs in 0..604_800_000L)
        require(groupId == null || state.groups.any { it.id == groupId && state.items[index].videoId in it.videoIds })
        val now=System.currentTimeMillis();val receipts=state.batchReceipts.filter { now-it.createdAtMs<=86_400_000 }
        if(receipts.size>=1000)return BatchAddResult.RECEIPT_LIMIT
        var updated=captureProgress(state).copy(currentIndex=index,activeGroupId=groupId,positionMs=positionMs,intent=if(play)PlaybackIntent.PLAY else PlaybackIntent.STOP,
            finished=false,playNext=null,version=state.version+1,batchReceipts=receipts+BatchReceipt(key,fingerprint,now))
        try {updated = commitState(updated)} catch (_:Exception){return BatchAddResult.STORAGE_ERROR}
        state=updated;invalidateRequest();currentTitle="";diagnostics="";error=null;refreshAttempted=false;status=PlaybackStatus.STOPPED;emit()
        if(play)prepareCurrent()
        return BatchAddResult.APPLIED
    }

    /** Group ordering is metadata: the playing item, position and generation stay intact. */
    fun reorderGroups(ids: List<String>, groupId: String?, expectedVersion: Long, key: String, fingerprint: String): BatchAddResult {
        if (closed) return BatchAddResult.STORAGE_ERROR
        batchReplay(key, fingerprint)?.let { return it }
        if (state.version != expectedVersion) return BatchAddResult.CONFLICT
        require(ids.distinct().size == ids.size)
        val groups = if (groupId == null) {
            require(ids.size == state.groups.size && ids.toSet() == state.groups.map { it.id }.toSet())
            val byId = state.groups.associateBy { it.id }; ids.map { byId.getValue(it) }
        } else {
            val group = state.groups.find { it.id == groupId } ?: throw IllegalArgumentException()
            require(ids.size == group.videoIds.size && ids.toSet() == group.videoIds.toSet())
            state.groups.map { if (it.id == groupId) it.copy(videoIds = ids) else it }
        }
        val now = System.currentTimeMillis(); val receipts = state.batchReceipts.filter { now-it.createdAtMs <= 86_400_000 }
        if (receipts.size >= 1000) return BatchAddResult.RECEIPT_LIMIT
        var updated = state.copy(groups = groups, version = state.version + if(groups == state.groups) 0 else 1,
            batchReceipts = receipts + BatchReceipt(key,fingerprint,now))
        try { updated = commitState(updated) } catch (_: Exception) { return BatchAddResult.STORAGE_ERROR }
        state = updated; emit(); return BatchAddResult.APPLIED
    }

    /** Remove this fixed list; shared videos, downloaded files and saved collections remain. */
    fun removeGroup(groupId: String, expectedVersion: Long, key: String, fingerprint: String): BatchAddResult {
        if(closed)return BatchAddResult.STORAGE_ERROR
        batchReplay(key,fingerprint)?.let { return it }
        if(state.version!=expectedVersion)return BatchAddResult.CONFLICT
        val group=state.groups.find { it.id==groupId } ?: throw IllegalArgumentException()
        val groups=state.groups.filterNot { it.id==groupId }
        val shared=groups.flatMap { it.videoIds }.toSet()
        val removed=group.videoIds.toSet()-shared
        val items=state.items.filterNot { it.videoId in removed }
        val current=state.currentItem
        val currentRemains=items.any { it.queueItemId==current?.queueItemId }
        val stop=state.activeGroupId==groupId || !currentRemains
        val index=if(items.isEmpty()) -1 else if(currentRemains) items.indexOfFirst { it.queueItemId==current?.queueItemId } else state.currentIndex.coerceAtMost(items.lastIndex)
        val now=System.currentTimeMillis();val receipts=state.batchReceipts.filter {now-it.createdAtMs<=86_400_000}
        if(receipts.size>=1000)return BatchAddResult.RECEIPT_LIMIT
        var updated=state.copy(items=items,groups=groups,currentIndex=index,activeGroupId=if(stop)null else state.activeGroupId,
            positionMs=if(currentRemains)state.positionMs else 0,intent=if(stop)PlaybackIntent.STOP else state.intent,
            finished=if(items.isEmpty() || stop)false else state.finished,
            childScreen=state.childScreen.copy(groupIds=state.childScreen.groupIds.filter { it!=groupId }),playNext=state.playNext?.takeIf { next -> items.any { it.videoId==next.videoId } },version=state.version+1,batchReceipts=receipts+BatchReceipt(key,fingerprint,now))
        try {updated = commitState(updated)} catch (_:Exception){return BatchAddResult.STORAGE_ERROR}
        state=updated
        if(stop){invalidateRequest();currentTitle="";diagnostics="";error=null;status=PlaybackStatus.STOPPED}
        emit();return BatchAddResult.APPLIED
    }

    /** Reorder only the pending suffix; receipt and queue commit share the same atomic file. */
    fun reorderPending(ids: List<String>, expectedVersion: Long, key: String, fingerprint: String): BatchAddResult {
        if (closed) return BatchAddResult.STORAGE_ERROR
        batchReplay(key,fingerprint)?.let { return it }
        if (expectedVersion != state.version) return BatchAddResult.CONFLICT
        val pending = state.pendingItems
        require(ids.size == pending.size && ids.distinct().size == ids.size && ids.toSet() == pending.map { it.queueItemId }.toSet())
        val now = System.currentTimeMillis()
        val receipts = state.batchReceipts.filter { now - it.createdAtMs <= 86_400_000 }
        if(receipts.size >= 1000) return BatchAddResult.RECEIPT_LIMIT
        val byId = pending.associateBy { it.queueItemId }
        var updated = state.copy(items = state.items.take(state.currentIndex + 1) + ids.map { byId.getValue(it) }, version = state.version + 1,
            batchReceipts = receipts + BatchReceipt(key,fingerprint,now))
        try { updated = commitState(updated) } catch (_: Exception) { return BatchAddResult.STORAGE_ERROR }
        state = updated
        emit()
        return BatchAddResult.APPLIED
    }

    fun movePending(queueItemId: String, moveUp: Boolean) {
        if (closed) return
        var updated = state.movePending(queueItemId, moveUp)
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    fun clearPending() {
        if (closed) return
        var updated = state.clearPending()
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    /** Seek only the exact current player request. Preserve PLAY/PAUSE and persist before player I/O. */
    fun seekCurrent(itemId: String, expectedVersion: Long, expectedGeneration: Long, positionMs: Long): SeekResult {
        if(closed || state.currentItem?.queueItemId!=itemId || state.version!=expectedVersion || generation!=expectedGeneration)return SeekResult.CONFLICT
        val duration=player.durationMs()
        if(startupStorageBlocked || state.finished || state.intent==PlaybackIntent.STOP || preparedItemId!=itemId || !player.isSeekable() || duration==null)return SeekResult.UNAVAILABLE
        require(positionMs in 0..duration)
        var previous=state.withPosition(player.currentPositionMs())
        val target=positionMs.coerceAtMost((duration-1).coerceAtLeast(0))
        var updated=previous.withPosition(target).copy(version=state.version+1)
        try {updated = commitState(updated)} catch(_:Exception){storageWarning=true;emit();return SeekResult.STORAGE_ERROR}
        val previousStatus=status
        state=updated
        // An in-buffer seek may keep playing without a new onPlaying event. Real player
        // callbacks own BUFFERING/PLAYING, including callbacks synchronous with seekTo.
        status=if(state.intent==PlaybackIntent.PLAY && previousStatus==PlaybackStatus.PLAYING) PlaybackStatus.PLAYING
            else if(state.intent==PlaybackIntent.PLAY) PlaybackStatus.BUFFERING else PlaybackStatus.PAUSED
        try {player.seekTo(target)} catch(_:Exception){
            state=previous;status=previousStatus
            return try {previous = commitState(previous);emit();SeekResult.FAILED} catch(_:Exception){storageWarning=true;emit();SeekResult.STORAGE_ERROR}
        }
        emit();return SeekResult.APPLIED
    }

    fun savePosition() {
        val current = state.currentItem ?: return
        if (preparedItemId != current.queueItemId || state.finished) return
        state = captureProgress(state)
        state = commitState(state)
    }

    private fun captureProgress(original: QueueState, completed: Boolean = false): QueueState {
        val item=original.currentItem ?: return original
        if(preparedItemId!=item.queueItemId)return original
        val previous=original.progress[item.videoId] ?: VideoProgress()
        val duration=player.durationMs()?.coerceAtLeast(0) ?: previous.durationMs
        val position=if(completed && duration>0)duration else player.currentPositionMs().coerceAtLeast(0)
        val watched=if(completed)VideoWatchStatus.WATCHED else if(position>0)VideoWatchStatus.IN_PROGRESS else previous.status
        val progress=previous.copy(positionMs=position,durationMs=duration,status=watched,
            lastPlayedAtMs=if(completed || original.intent==PlaybackIntent.PLAY || position!=previous.positionMs)System.currentTimeMillis() else previous.lastPlayedAtMs)
        val history=original.progress.toMutableMap()
        if(item.videoId !in history && history.size>=10_000) {
            val oldest=history.filter { it.value.bookmarks.isEmpty() && it.key !in original.favorites }.minByOrNull { it.value.lastPlayedAtMs }?.key
            if(oldest!=null)history.remove(oldest) else return original.withPosition(position)
        }
        history[item.videoId]=progress
        return original.withPosition(position).copy(progress=history)
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        extractionJob?.cancel()
    }

    override fun onEnded(generation: Long) {
        if (!isCurrentPlayerRequest(generation)) return
        val stopping = state.stopAfterCurrent
        state = captureProgress(state, completed = true).next()
        if(stopping)state=state.copy(intent=PlaybackIntent.STOP,stopAfterCurrent=false)
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (state.intent == PlaybackIntent.PLAY && !state.finished) {
            prepareCurrent()
        } else {
            invalidateRequest()
            status = PlaybackStatus.ENDED
            emit()
        }
    }

    override fun onError(generation: Long, failure: PlaybackFailure) {
        if (!isCurrentPlayerRequest(generation)) return
        savePosition()
        if (failure.canRefresh && !refreshAttempted && state.intent != PlaybackIntent.STOP) {
            refreshAttempted = true
            prepareCurrent(preserveRefreshAttempt = true)
            return
        }
        preparedItemId = null
        status = PlaybackStatus.ERROR
        error = failure
        emit()
    }

    override fun onFirstFrame(generation: Long) {
        if (!isCurrentPlayerRequest(generation)) return
        status = if (state.intent == PlaybackIntent.PLAY) {
            PlaybackStatus.PLAYING
        } else {
            PlaybackStatus.PAUSED
        }
        error = null
        emit()
    }

    override fun onPlaying(generation: Long) {
        if (!isCurrentPlayerRequest(generation) || state.intent != PlaybackIntent.PLAY) return
        status = PlaybackStatus.PLAYING
        error = null
        emit()
    }

    override fun onBuffering(generation: Long) {
        if (!isCurrentPlayerRequest(generation) || state.intent != PlaybackIntent.PLAY) return
        status = PlaybackStatus.BUFFERING
        emit()
    }

    override fun onDiagnostics(generation: Long, diagnostics: String) {
        if (!isCurrentPlayerRequest(generation)) return
        this.diagnostics = diagnostics
        emit()
    }

    internal fun handleResolved(
        generation: Long,
        queueItemId: String,
        resolved: ResolvedVideo,
    ) {
        if (!isCurrentExtraction(generation, queueItemId)) return
        try {
            preparedItemId = queueItemId
            currentTitle = resolved.title
            localPlayback = resolved.video.url.startsWith("file:")
            diagnostics = resolved.selectionDescription
            player.prepare(
                generation = generation,
                video = resolved,
                positionMs = state.positionMs,
                playWhenReady = state.intent == PlaybackIntent.PLAY,
            )
            status = if (state.intent == PlaybackIntent.PLAY) {
                PlaybackStatus.BUFFERING
            } else {
                PlaybackStatus.PAUSED
            }
            error = null
        } catch (error: Exception) {
            preparedItemId = null
            status = PlaybackStatus.ERROR
            this.error = PlaybackFailure(PlaybackErrorKind.PLAYBACK)
        }
        emit()
    }

    internal fun handleSourceError(
        generation: Long,
        queueItemId: String,
        cause: Throwable,
    ) {
        if (!isCurrentExtraction(generation, queueItemId)) return
        preparedItemId = null
        val failure = if (cause is YouTubeSourceException) {
            cause.failure
        } else {
            PlaybackFailure(PlaybackErrorKind.EXTRACTOR)
        }
        if (failure.stopRequested) {
            state = state.stop()
            refreshAttempted = false
            persist()
        }
        status = PlaybackStatus.ERROR
        error = failure
        emit()
    }

    private fun prepareCurrent(preserveRefreshAttempt: Boolean = false) {
        val current = state.currentItem ?: return
        if (!authorizationGate() && currentNeedsYouTubeSession()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        if (!preserveRefreshAttempt) refreshAttempted = false
        invalidateRequest()
        val requestGeneration = generation
        status = PlaybackStatus.LOADING
        error = null
        emit()
        extractionJob = scope.launch {
            try {
                val resolved = withContext(ioDispatcher) {
                    if (current.local) (localSource ?: error("LOCAL_SOURCE_UNAVAILABLE")).resolve(current.videoId) else source.resolve(current.videoId)
                }
                handleResolved(requestGeneration, current.queueItemId, resolved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleSourceError(requestGeneration, current.queueItemId, error)
            }
        }
    }

    private fun invalidateRequest() {
        localPlayback = false
        generation++
        extractionJob?.cancel()
        extractionJob = null
        preparedItemId = null
        player.stop()
    }

    private fun isCurrentExtraction(generation: Long, queueItemId: String): Boolean =
        !closed && this.generation == generation && state.currentItem?.queueItemId == queueItemId

    private fun isCurrentPlayerRequest(generation: Long): Boolean {
        val current = state.currentItem ?: return false
        return !closed && this.generation == generation && preparedItemId == current.queueItemId
    }

    private fun persist() {
        state = state.copy(version = state.version + 1)
        state = commitState(state)
        startupStorageBlocked = false
    }

    private fun emit() {
        updatedAtMs = System.currentTimeMillis()
        listener?.invoke(currentSnapshot())
    }

    fun currentSnapshot(): CoordinatorSnapshot = CoordinatorSnapshot(
        queueState = if(preparedItemId==state.currentItem?.queueItemId && preparedItemId!=null && !state.finished && player.durationMs()!=null && player.isSeekable()) state.withPosition(player.currentPositionMs()) else state,
        status = status,
        localPlayback = localPlayback,
        currentTitle = currentTitle,
        diagnostics = diagnostics,
        error = error,
        generation = generation,
        storageWarning = storageWarning,
        updatedAtMs = updatedAtMs,
        durationMs = if(preparedItemId!=null && preparedItemId==state.currentItem?.queueItemId)player.durationMs() else null,
        bufferedPositionMs = if(preparedItemId!=null && preparedItemId==state.currentItem?.queueItemId)player.bufferedPositionMs() else null,
        seekable = preparedItemId!=null && preparedItemId==state.currentItem?.queueItemId && !state.finished && state.intent!=PlaybackIntent.STOP && player.isSeekable(),
    )
}
