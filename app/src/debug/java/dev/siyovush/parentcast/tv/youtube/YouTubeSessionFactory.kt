package dev.siyovush.parentcast.tv.youtube

import android.content.Context

object YouTubeSessionFactory {
    fun create(context: Context): YouTubeSession = DebugYouTubeSession(context)
}
