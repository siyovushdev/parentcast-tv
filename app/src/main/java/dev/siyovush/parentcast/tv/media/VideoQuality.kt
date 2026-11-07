package dev.siyovush.parentcast.tv.media

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpResponseParser
import java.security.MessageDigest

internal data class VideoQuality(val id:String,val height:Int,val sizeBytes:Long?,internal val resolved:ResolvedVideo)
internal data class VideoPreview(val videoId:String,val title:String,val durationSeconds:Long,val qualities:List<VideoQuality>,val thumbnailUrl:String?=null)
internal fun interface QualitySource { suspend fun preview(videoId:String):VideoPreview }

/** Options come from extractor formats, never from invented 480/720/1080 labels. */
internal object VideoQualities {
    fun parse(root:JsonNode,id:String,vk:Boolean=false):VideoPreview {
        val formats=root.path("formats").filter { !it.path("has_drm").asBoolean(false) && it.path("protocol").asText() in setOf("http","https") }
        val audio=formats.filter { it.path("vcodec").asText() in setOf("none","") && it.path("acodec").asText().startsWith("mp4a") }
            .maxByOrNull { it.path("abr").asDouble(it.path("tbr").asDouble(0.0)) }
        val videos=formats.filter { it.path("vcodec").asText().startsWith("avc1") && it.path("ext").asText()=="mp4" && it.path("height").asInt() in 1..1080 &&
            (it.path("acodec").asText().startsWith("mp4a") || it.path("acodec").asText() in setOf("none","") && audio!=null) }
        val heights=videos.map { it.path("height").asInt() }.distinct().sorted()
        val preferred=heights.filter { it <= 480 || it in setOf(720,1080) }.ifEmpty { listOfNotNull(heights.lastOrNull()) }
        val choices=preferred.mapNotNull { height ->
            val selected=videos.filter { it.path("height").asInt()==height }.maxByOrNull { it.path("tbr").asDouble(0.0) } ?: return@mapNotNull null
            val combined=selected.path("acodec").asText().startsWith("mp4a")
            val copy=root.deepCopy<ObjectNode>()
            val requested=copy.putArray("requested_formats").add(selected)
            if(!combined)requested.add(audio)
            val parsed=if(vk) VkVideoParser().parse(copy,id) else YtDlpResponseParser().parse(copy,id)
            val config=selected.path("format_id").asText()+"|"+(if(combined)"mux" else audio!!.path("format_id").asText())+"|"+height
            val key=MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
            val tracks=listOfNotNull(parsed.video,parsed.audio)
            VideoQuality(key,height,if(tracks.all { it.sizeBytes!=null })tracks.sumOf { it.sizeBytes!! } else null,parsed)
        }
        require(choices.isNotEmpty()) { "NO_SUPPORTED_QUALITY" }
        val first=choices.first().resolved
        val thumbnail=if(!vk)"https://i.ytimg.com/vi/$id/hqdefault.jpg" else root.path("thumbnail").asText().takeIf { it.startsWith("https://") && it.length<=2048 }
        return VideoPreview(id,first.title,first.durationSeconds,choices,thumbnail)
    }
}
