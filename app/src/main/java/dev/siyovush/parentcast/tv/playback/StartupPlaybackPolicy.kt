package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.queue.QueueState

/** Applies only when a new coordinator restores a persisted queue. Explicit PLAY remains separate. */
object StartupPlaybackPolicy {
    /** A live handoff follows the owner's safe intent, never a possibly stale PLAY on disk. */
    fun resumeLiveOwner(snapshot: CoordinatorSnapshot): Boolean =
        snapshot.queueState.intent == PlaybackIntent.PLAY && !snapshot.storageWarning &&
            snapshot.queueState.currentItem != null && !snapshot.queueState.finished

    fun restore(state: QueueState, autoResume: Boolean): QueueState =
        if (!autoResume && state.currentItem != null && !state.finished && state.intent == PlaybackIntent.PLAY) {
            state.copy(intent = PlaybackIntent.PAUSE, version = state.version + 1)
        } else {
            state
        }
}
