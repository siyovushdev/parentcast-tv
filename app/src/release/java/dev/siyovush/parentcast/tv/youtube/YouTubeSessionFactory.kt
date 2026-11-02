package dev.siyovush.parentcast.tv.youtube

import android.content.Context

object YouTubeSessionFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): YouTubeSession = DisabledYouTubeSession
}
