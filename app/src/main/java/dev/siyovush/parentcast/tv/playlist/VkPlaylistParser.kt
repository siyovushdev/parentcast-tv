package dev.siyovush.parentcast.tv.playlist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.media.OnlineMediaIds

/** Anonymous enumeration does not establish per-video availability; unknown stays UNKNOWN. */
class VkPlaylistParser {
    private val mapper = ObjectMapper()
    fun parse(output: String, diagnostics: String, exitCode: Int, playlistId: String): PlaylistResult {
        require(OnlineMediaIds.isVkPlaylist(playlistId))
        val objects=output.lineSequence().filter { it.startsWith('{') }.mapNotNull { runCatching { mapper.readTree(it) }.getOrNull() }.toList()
        val root=objects.lastOrNull { it.path("_type").asText()=="playlist" }
        val raw=playlistId.removePrefix("vkpl:").split('_')
        val valid=root!=null && root.path("id").asText()=="${raw[0]}_playlist_${raw[1]}" && root.path("parentcast_schema").asInt()==1
        val count=root?.path("parentcast_reported_count")?.takeIf { it.isIntegralNumber && it.asLong() in 0..Int.MAX_VALUE }?.asInt()
        val entries=root?.path("entries")?.takeIf { it.isArray }?.take(501)?.mapIndexed { n,e -> entry(e,n+1) }.orEmpty()
        val warning=diagnostics.lineSequence().filter {
            ("WARNING:" in it || "ERROR:" in it || "Skipping " in it || "Traceback" in it) &&
                !("ffmpeg-location" in it && "does not exist! Continuing without ffmpeg" in it)
        }.joinToString(" ").replace(Regex("https?://\\S+"),"<URL>")
            .replace(Regex("(?i)(cookie|authorization|token|signature|sig)=[^\\s&]+"),"$1=<redacted>").take(600)
        val forbidden=root?.path("availability")?.asText() in setOf("private","unlisted","needs_auth","subscriber_only","premium_only")
        val (status,reason)=when {
            (count ?: 0)>500 || entries.size>500 -> PlaylistStatus.LIMIT_EXCEEDED to "В плейлисте VK более 500 элементов. Импорт запрещён; список не обрезается молча."
            !valid || forbidden -> PlaylistStatus.ERROR to "Не подтверждён ответ публичного VK-плейлиста. Закрытые списки и вход в аккаунт не поддерживаются."
            root!!.path("parentcast_page_error").asBoolean(false) -> PlaylistStatus.PARTIAL to "VK: не получена очередная страница или изменился её формат. Сохранены ${entries.size} элементов; полный импорт не подтверждён."
            count==null || count!=entries.size || root.path("parentcast_changed").asBoolean(false) || exitCode!=0 || warning.isNotBlank() ->
                PlaylistStatus.PARTIAL to (warning.ifBlank { "VK: получено ${entries.size}, заявлено ${count ?: "неизвестно"}; полнота или неизменность списка не подтверждена." })
            else -> PlaylistStatus.COMPLETE to null
        }
        return PlaylistResult(root?.path("title")?.takeIf { it.isTextual }?.asText()?.take(500) ?: "Плейлист VK",
            if(valid && !forbidden)entries else entries.map { it.copy(selectable=false) },status,reason,count,MediaProvider.VK)
    }
    private fun entry(node: JsonNode,index: Int): PlaylistEntry {
        val id=runCatching { OnlineMediaIds.vk(node.path("id").asText()) }.getOrNull()
        val title=node.path("title").takeIf { it.isTextual }?.asText()?.take(500).orEmpty()
        val duration=node.path("duration").takeIf { it.isNumber && it.asDouble().let { d -> d.isFinite() && d>0 && d<315_360_000 } }?.asLong()
        val availability=node.path("availability").asText()
        val unsupported=node.path("has_drm").asBoolean(false) || node.path("is_live").asBoolean(false) || node.path("was_live").asBoolean(false) ||
            node.path("live_status").asText() in setOf("is_live","is_upcoming","post_live") || availability=="unlisted"
        val unavailable=id==null || node.path("ie_key").asText() !in setOf("VK","") || availability in setOf("private","needs_auth","subscriber_only","premium_only","unavailable") ||
            title.lowercase() in setOf("[deleted video]","[private video]","видео удалено","видео недоступно")
        val state=when { unavailable -> PlaylistAvailability.UNAVAILABLE;unsupported -> PlaylistAvailability.UNSUPPORTED;availability=="public" -> PlaylistAvailability.AVAILABLE;else -> PlaylistAvailability.UNKNOWN }
        return PlaylistEntry(index,id,title.ifBlank { "Видео VK: ${id?.removePrefix("vk:") ?: index}" },state,
            !unavailable && !unsupported && title.isNotBlank() && duration!=null,duration,null)
    }
}
