package dev.siyovush.parentcast.tv.queue

import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import java.util.UUID

enum class QueueGroupSource { YOUTUBE_PLAYLIST, VK_PLAYLIST, COLLECTION }

/** Fixed ordered references to unique queue videos. Shared videos are stored/downloaded once. */
data class QueueGroup(val id: String, val name: String, val videoIds: List<String>, val source: QueueGroupSource) {
    init {
        require(id.matches(Regex("g_[0-9a-f]{32}")))
        require(name.isNotBlank() && name.length <= 200 && name.none(Character::isISOControl))
        require(videoIds.size in 1..500 && videoIds.distinct().size == videoIds.size && videoIds.all(OnlineMediaIds::isOnline))
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
