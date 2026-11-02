package dev.siyovush.parentcast.tv.youtube

import android.content.Context

object YouTubeSessionFactory {
    private var session: DebugYouTubeSession? = null

    @Synchronized
    fun create(context: Context): YouTubeSession = session
        ?: DebugYouTubeSession(context.applicationContext).also { session = it }
}
