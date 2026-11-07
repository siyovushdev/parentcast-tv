package dev.siyovush.parentcast.tv.streaming

import dev.siyovush.parentcast.tv.media.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnlinePlaybackTest {
    @get:Rule val temporary=TemporaryFolder()
    private val id="vk:-211491295_456239183"
    private val quality="a".repeat(64)
    private fun metadata()=VideoPreview(id,"Selected video",100,listOf(VideoQuality(quality,720,8,
        ResolvedVideo(id,"Selected video",100,StreamSpec("https://a.okcdn.ru/media?sig=fixture-only","video/mp4",emptyMap(),8,"stable",MediaProvider.VK),null,"fixture"))))
    private val forbidden=object:YouTubeSource {override suspend fun resolve(videoId:String):ResolvedVideo=error("Must bypass storage/fallback")}
    @Test fun onlineBypassesUnavailableStorageAndPersistsOnlyChoiceForResume()=runBlocking {
        val root=temporary.newFolder();val journal=File(root,"online.json");var requests=0
        val source=OnlinePlayback(journal,DisabledYouTubeSession){_,_->requests++;metadata()}
        source.select(id,quality)
        val video=source.source(forbidden).resolve(id)
        assertTrue(video.video.url.startsWith("parentcast-online://"));assertNotNull(video.video.onlineKey);assertNull(video.video.sharedCacheKey)
        assertFalse(journal.readText().contains("https:"));assertFalse(journal.readText().contains("sig="));assertEquals(listOf("online.json"),root.list()!!.toList())
        val restored=OnlinePlayback(journal,DisabledYouTubeSession){_,_->requests++;metadata()}
        assertTrue(restored.enabled(id));assertEquals(720,restored.height(id));restored.source(forbidden).resolve(id);assertEquals(2,requests)
        restored.forget(id);assertFalse(restored.enabled(id));assertFalse(OnlinePlayback(journal,DisabledYouTubeSession){_,_->metadata()}.enabled(id))
    }
    @Test fun invalidQualityDoesNotSaveModeAndCorruptSelectionDoesNotEnablePlayback()=runBlocking {
        val root=temporary.newFolder();val journal=File(root,"online.json")
        val playback=OnlinePlayback(journal,DisabledYouTubeSession){_,_->metadata()}
        assertThrows(IllegalStateException::class.java){runBlocking {playback.select(id,"b".repeat(64))}}
        assertFalse(journal.exists());journal.writeText("{broken")
        val damaged=OnlinePlayback(journal,DisabledYouTubeSession){_,_->metadata()}
        assertFalse(damaged.enabled(id));assertThrows(IllegalStateException::class.java){runBlocking{damaged.select(id,quality)}}
        Unit
    }
}
