package dev.siyovush.parentcast.tv.prepared

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Only framework-provided app directories; never broad storage access or user-supplied paths. */
internal class AndroidMediaVolumes(private val app: Context) {
    fun list(): List<MediaVolume> {
        val manager=app.getSystemService(StorageManager::class.java)
        return app.getExternalFilesDirs(null).filterNotNull().mapNotNull { directory ->
            val volume=manager.getStorageVolume(directory) ?: return@mapNotNull null
            val uuid=volume.uuid ?: return@mapNotNull null
            if(!volume.isRemovable || volume.isEmulated || !uuid.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return@mapNotNull null
            val state=Environment.getExternalStorageState(directory)
            val mounted=state in setOf(Environment.MEDIA_MOUNTED,Environment.MEDIA_MOUNTED_READ_ONLY)
            val target=File(directory,"prepared-programme")
            if(state==Environment.MEDIA_MOUNTED && !target.exists()) target.mkdirs()
            MediaVolume("sd:$uuid",volume.getDescription(app).take(100),target.takeIf { mounted },true,mounted,
                state==Environment.MEDIA_MOUNTED && target.isDirectory && target.canWrite(), directory.totalSpace,directory.usableSpace)
        }
    }
}
