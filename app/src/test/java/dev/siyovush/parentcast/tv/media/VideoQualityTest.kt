package dev.siyovush.parentcast.tv.media

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class VideoQualityTest {
    private val mapper=ObjectMapper()
    private fun root(heights:List<Int>,revision:String="one")=mapper.createObjectNode().apply {
        put("id","video000001");put("title","<video>");put("duration",50)
        putArray("formats").apply { heights.forEach { h->addObject().apply {
            put("format_id","h$h");put("protocol","https");put("url","https://r.googlevideo.com/v?sig=$revision&lmt=123")
            put("vcodec","avc1.64001f");put("acodec","mp4a.40.2");put("ext","mp4");put("height",h);put("filesize",h*100L)
        } } }
    }
    @Test fun exposesOnlyActualQualitiesAndOpaqueStableIds() {
        val first=VideoQualities.parse(root(listOf(144,240,360,480,720,1080,2160)),"video000001")
        val changed=VideoQualities.parse(root(listOf(144,240,360,480,720,1080,2160),"new-signed-url"),"video000001")
        assertEquals(listOf(144,240,360,480,720,1080),first.qualities.map { it.height });assertEquals(first.qualities.map{it.id},changed.qualities.map{it.id})
        assertTrue(first.qualities.all { it.id.matches(Regex("[a-f0-9]{64}")) });assertEquals(14400L,first.qualities.first().sizeBytes)
    }
    @Test fun lowerChoicesRemainAvailableBeside480WithoutInventedQualities() {
        val choices=VideoQualities.parse(root(listOf(240,360,480,1080)),"video000001").qualities
        assertEquals(listOf(240,360,480,1080),choices.map { it.height })
        assertEquals(listOf(24000L,36000L,48000L,108000L),choices.map { it.sizeBytes })
        assertFalse(choices.any { it.height==720 })
    }
    @Test fun preservesShortLowResolutionVideosAndRejectsDrmHls() {
        assertEquals(240,VideoQualities.parse(root(listOf(240)),"video000001").qualities.single().height)
        val value=root(listOf(720));(value.path("formats")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("has_drm",true)
        assertThrows(IllegalArgumentException::class.java){VideoQualities.parse(value,"video000001")}
        (value.path("formats")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("has_drm",false).put("protocol","m3u8_native")
        assertThrows(IllegalArgumentException::class.java){VideoQualities.parse(value,"video000001")}
    }
}
