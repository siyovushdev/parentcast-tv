package dev.siyovush.parentcast.tv.prepared

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.siyovush.parentcast.tv.youtube.safeDiagnostic
import dev.siyovush.parentcast.tv.youtube.YouTubeSessionFactory
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpYouTubeSource
import kotlinx.coroutines.*
import java.io.File

internal object PreparedRuntime {
    @Volatile private var instance: PreparedLibrary? = null
    @Volatile private var sharedSessionInstance: dev.siyovush.parentcast.tv.youtube.YouTubeSession? = null
    fun session(context: Context): dev.siyovush.parentcast.tv.youtube.YouTubeSession = sharedSessionInstance ?: synchronized(this) {
        sharedSessionInstance ?: YouTubeSessionFactory.create(context.applicationContext).also { sharedSessionInstance = it }
    }
    fun get(context: Context, sharedSession: dev.siyovush.parentcast.tv.youtube.YouTubeSession? = null): PreparedLibrary = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val session = sharedSession ?: session(app)
            val store = PreparedStore(File(app.noBackupFilesDir, "prepared-programme"),AndroidMediaVolumes(app)::list)
            val budget = MediaBudget(store.directory,storage=store.storage)
            PreparedLibrary(store,
                dev.siyovush.parentcast.tv.media.OnlineVideoSource(YtDlpYouTubeSource(app, session, publicOnly = true), dev.siyovush.parentcast.tv.media.VkVideoSource(app)), HttpTrackDownloader(session, budget, transferEvent={event,offset -> android.util.Log.i("ParentCastTransfer","$event offset=$offset")}),
                CoroutineScope(SupervisorJob() + Dispatchers.IO), ::validate,
                wakeService = { android.os.Handler(app.mainLooper).post {
                    try { app.startForegroundService(Intent(app, PreparationService::class.java)) }
                    catch (_: Exception) {
                        instance?.serviceUnavailable()
                        android.util.Log.w("ParentCastPreparation", "Foreground start rejected; preparation paused")
                    }
                } }, budget = budget, onFailure = { id, error -> android.util.Log.w("ParentCastPreparation", "$id: ${error.safeDiagnostic()}") }).also { instance = it;dev.siyovush.parentcast.tv.streaming.StreamingRuntime.get(app,it,session) }
        }
    }
    private fun validate(file: File, audio: Boolean, durationSeconds: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val matching = formats.filter { it.getString(MediaFormat.KEY_MIME) == if (audio) "audio/mp4a-latm" else "video/avc" }
            check(matching.isNotEmpty()) { "UNSUPPORTED" }
            check(matching.all { it.containsKey(MediaFormat.KEY_DURATION) && it.getLong(MediaFormat.KEY_DURATION) >= (durationSeconds - 5).coerceAtLeast(1) * 1_000_000 }) { "DOWNLOAD_FAILED" }
        } finally { extractor.release() }
    }
}
