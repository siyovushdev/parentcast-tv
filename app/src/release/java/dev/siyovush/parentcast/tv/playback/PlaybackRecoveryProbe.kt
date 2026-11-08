package dev.siyovush.parentcast.tv.playback

import android.content.Context

/** Acceptance fault injection is absent from release builds. */
internal object PlaybackRecoveryProbe {
    fun poll(context:Context,coordinator:PlaybackCoordinator,online:(String)->Boolean) = Unit
}
