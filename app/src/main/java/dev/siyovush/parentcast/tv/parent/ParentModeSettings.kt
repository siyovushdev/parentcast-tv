package dev.siyovush.parentcast.tv.parent

import android.content.Context

/** Reversible app preferences only. Device policy and HOME selection are handled separately. */
class ParentModeSettings(context: Context) {
    private val preferences = context.getSharedPreferences("parent-mode", Context.MODE_PRIVATE)

    var childEnabled: Boolean
        get() = preferences.getBoolean("child_enabled", false)
        set(value) {
            check(preferences.edit().putBoolean("child_enabled", value).commit()) {
                "Could not persist child mode preference"
            }
        }

    var autoResumeAfterBoot: Boolean
        get() = preferences.getBoolean("auto_resume_after_boot", false)
        set(value) {
            check(preferences.edit().putBoolean("auto_resume_after_boot", value).commit()) {
                "Could not persist auto resume preference"
            }
        }
}
