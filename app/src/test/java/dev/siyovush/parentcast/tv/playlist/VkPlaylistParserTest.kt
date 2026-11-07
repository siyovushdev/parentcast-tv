package dev.siyovush.parentcast.tv.playlist

import com.fasterxml.jackson.databind.ObjectMapper
import dev.siyovush.parentcast.tv.media.MediaProvider
import org.junit.Assert.*
import org.junit.Test

class VkPlaylistParserTest {
    private val mapper=ObjectMapper()
    private fun root(n: Int=3) = mapper.createObjectNode().apply {
        put("_type","playlist");put("id","-1_playlist_2");put("title","<script>VK</script>")
        put("parentcast_schema",1);put("parentcast_reported_count",n)
        putArray("entries").also { a -> repeat(n) { i -> a.addObject().put("id","-1_${i+1}").put("ie_key","VK").put("title","Видео ${i+1}").put("duration",116) } }
    }
    private fun parse(r: com.fasterxml.jackson.databind.JsonNode, err: String="", exit: Int=0)=VkPlaylistParser().parse(r.toString(),err,exit,"vkpl:-1_2")
    @Test fun orderedMetadataKeepsUnknownAvailabilityHonest() {
        val result=parse(root(201))
        assertEquals(PlaylistStatus.COMPLETE,result.status);assertEquals(MediaProvider.VK,result.provider)
        assertEquals(201,result.reportedCount);assertEquals((1..201).map { "vk:-1_$it" },result.entries.map { it.videoId })
        assertTrue(result.entries.all { it.availability==PlaylistAvailability.UNKNOWN && it.selectable })
        assertEquals("<script>VK</script>",result.title)
    }
    @Test fun missingPageChangedTotalWarningsOrExitArePartial() {
        val r=root();r.put("parentcast_reported_count",4)
        assertEquals(PlaylistStatus.PARTIAL,parse(r).status)
        for(message in listOf("Skipping 1 unavailable videos", "WARNING: page failed https://private.example/signed?token=secret", "ERROR: second page")) {
            val result=parse(root(),message);assertEquals(PlaylistStatus.PARTIAL,result.status)
            assertFalse(result.reason!!.contains("private.example"));assertFalse(result.reason!!.contains("secret"))
        }
        assertEquals(PlaylistStatus.PARTIAL,parse(root(),exit=1).status)
        val failed=parse(root().put("parentcast_page_error",true))
        assertEquals(PlaylistStatus.PARTIAL,failed.status);assertEquals(3,failed.entries.size)
        assertTrue(failed.reason!!.contains("страница"))
        assertEquals(PlaylistStatus.PARTIAL,parse(root().put("parentcast_changed",true)).status)
        assertEquals(PlaylistStatus.PARTIAL,parse(root().apply { remove("parentcast_reported_count") }).status)
    }
    @Test fun exactLimitAndSchemaFailureNeverSilentlyImport() {
        assertEquals(PlaylistStatus.COMPLETE,parse(root(500)).status)
        assertEquals(PlaylistStatus.LIMIT_EXCEEDED,parse(root(501)).status)
        assertEquals(PlaylistStatus.LIMIT_EXCEEDED,parse(root(0).put("parentcast_reported_count",1000)).status)
        for(r in listOf(root().put("parentcast_schema",2), root().put("id","-2_playlist_2"),root().put("availability","private"))) {
            val result=parse(r);assertEquals(PlaylistStatus.ERROR,result.status);assertFalse(result.entries.any { it.selectable })
        }
    }
    @Test fun unavailableUnlistedAndLiveAreNeverSelectable() {
        val r=root(6);val rows=r.path("entries")
        (rows[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("availability","public")
        (rows[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("availability","unlisted")
        (rows[2] as com.fasterxml.jackson.databind.node.ObjectNode).put("availability","private")
        (rows[3] as com.fasterxml.jackson.databind.node.ObjectNode).put("title","[Deleted video]")
        (rows[4] as com.fasterxml.jackson.databind.node.ObjectNode).put("is_live",true)
        (rows[5] as com.fasterxml.jackson.databind.node.ObjectNode).remove("duration")
        val entries=parse(r).entries
        assertEquals(PlaylistAvailability.AVAILABLE,entries[0].availability);assertTrue(entries[0].selectable)
        assertEquals(PlaylistAvailability.UNSUPPORTED,entries[1].availability)
        assertTrue(entries.drop(1).none { it.selectable })
    }
}
