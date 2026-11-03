package dev.siyovush.parentcast.tv.prepared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.siyovush.parentcast.tv.R
import kotlinx.coroutines.*

class PreparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var latestStartId = 0
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("preparation", "Подготовка программы", NotificationManager.IMPORTANCE_LOW))
        startForeground(71, notification("Подготовка выбранных видео"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private fun notification(message: String) = Notification.Builder(this, "preparation")
        .setSmallIcon(R.drawable.ic_launcher).setContentTitle("ParentCast TV").setContentText(message).setOngoing(true).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (job?.isActive != true) {
            val library = PreparedRuntime.get(this)
            job = scope.launch {
                val progress = launch {
                    while (isActive) {
                        val s = library.snapshot()
                        val ready = s.plan.count { s.entries[it]?.status == PreparationStatus.READY }
                        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            getSystemService(NotificationManager::class.java).notify(71, notification("Готово $ready из ${s.plan.size}"))
                        }
                        delay(2_000)
                    }
                }
                try {
                    do {
                        try { withContext(Dispatchers.IO) { library.runWorker() } }
                        catch (cancelled: CancellationException) { if (!isActive) throw cancelled }
                        catch (_: Exception) { break }
                    } while (library.hasPending())
                } finally { progress.cancel(); stopSelfResult(latestStartId) }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        PreparedRuntime.get(this).serviceUnavailable(); job?.cancel(); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
