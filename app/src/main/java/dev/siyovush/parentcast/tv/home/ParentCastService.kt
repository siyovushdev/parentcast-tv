package dev.siyovush.parentcast.tv.home

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import dev.siyovush.parentcast.tv.R
import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.queue.PlaybackIntent

class ParentCastService : Service() {
    internal inner class LocalBinder : Binder() { val runtime get() = this@ParentCastService.runtime }
    private lateinit var runtime: ParentCastRuntime
    private val binder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("parent-control", "Домашний пульт ParentCast", NotificationManager.IMPORTANCE_LOW))
        publish(null)
        runtime = ParentCastRuntime(this, ::publish)
    }
    private fun publish(snapshot: CoordinatorSnapshot?) {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, dev.siyovush.parentcast.tv.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playing = snapshot?.queueState?.intent == PlaybackIntent.PLAY
        val notification = Notification.Builder(this, "parent-control").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ParentCast TV").setContentText(if (playing) "Программа воспроизводится · домашний пульт включён" else "Домашний пульт включён")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(72, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (playing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { if (::runtime.isInitialized) runtime.close(); super.onDestroy() }
}
