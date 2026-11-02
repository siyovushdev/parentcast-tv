package dev.siyovush.parentcast.tv.parent

import android.content.Context

/** Persistent rate-limit metadata only; no credentials or parent session survives process death. */
class SharedParentAttemptStore(context: Context) : ParentAttemptStore {
    private val preferences = context.getSharedPreferences("parent-access", Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): ParentAttemptState = ParentAttemptState(
        preferences.getInt("failures", 0),
        preferences.getLong("window_start_ms", 0),
        preferences.getLong("last_observed_ms", 0),
    )

    @Synchronized
    override fun write(state: ParentAttemptState) {
        check(preferences.edit().putInt("failures", state.failures)
            .putLong("window_start_ms", state.windowStartMs)
            .putLong("last_observed_ms", state.lastObservedMs).commit()) {
            "Could not persist parent access attempt"
        }
    }
}
