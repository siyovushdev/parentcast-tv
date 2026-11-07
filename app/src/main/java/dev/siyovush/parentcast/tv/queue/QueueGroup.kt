package dev.siyovush.parentcast.tv.queue

import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import java.util.UUID

enum class QueueGroupSource { YOUTUBE_PLAYLIST, VK_PLAYLIST, COLLECTION, CUSTOM }

/** Fixed ordered references to unique queue videos. Shared videos are stored/downloaded once. */
data class QueueGroup(val id: String, val name: String, val videoIds: List<String>, val source: QueueGroupSource,
    val favorite: Boolean = false, val coverVideoId: String? = null, val coverImageId: String? = null) {
    init {
        require(id.matches(Regex("g_[0-9a-f]{32}")))
        require(name.isNotBlank() && name.length <= 200 && name.none(Character::isISOControl))
        require(videoIds.size in 1..500 && videoIds.distinct().size == videoIds.size && videoIds.all { OnlineMediaIds.isOnline(it) || source == QueueGroupSource.CUSTOM && validProgrammeVideoId(it) })
        require(coverVideoId == null || coverVideoId in videoIds)
        require(coverImageId == null || coverImageId.matches(Regex("[0-9a-f]{64}")))
        require(coverVideoId == null || coverImageId == null)
    }
    companion object {
        fun fixed(origin: String, name: String, ids: List<String>, source: QueueGroupSource): QueueGroup {
            val unique = ids.distinct()
            val id = "g_" + UUID.nameUUIDFromBytes((origin + "|" + unique.joinToString(",")).toByteArray()).toString().replace("-", "")
            return QueueGroup(id, cleanVideoTitle(name).take(200).ifBlank { "Плейлист" }, unique, source)
        }
    }
}

fun cleanVideoTitle(value: String): String = value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(500)
