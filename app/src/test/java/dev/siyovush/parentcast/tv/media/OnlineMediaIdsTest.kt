package dev.siyovush.parentcast.tv.media

import org.junit.Assert.*
import org.junit.Test

class OnlineMediaIdsTest {
    @Test fun videoAliasesDeduplicateAndCannotCollideWithYoutube() {
        val expected="vk:-227392256_456239129"
        for(url in listOf("https://vkvideo.ru/video-227392256_456239129?pl=-227392256_1", "https://vk.com/video-227392256_456239129", "https://m.vkvideo.ru/video-0227392256_0456239129/")) assertEquals(expected,OnlineMediaIds.parseLink(url))
        assertEquals("vk_1_123456",OnlineMediaIds.parseLink("https://youtu.be/vk_1_123456"))
        assertFalse(OnlineMediaIds.isVk("vk_1_123456"))
        assertEquals("https://vkvideo.ru/video-227392256_456239129",OnlineMediaIds.videoUrl(expected))
    }
    @Test fun providedPlaylistLinksHaveOneCanonicalTarget() {
        for(url in listOf("https://vkvideo.ru/playlist/-227392256_1/", "https://vkvideo.ru/playlist/-227392256_1/season_0", "https://vkvideo.ru/video-227392256_456239129?pl=-227392256_1", "https://vk.com/video/playlist/-227392256_1")) assertEquals("vkpl:-227392256_1",OnlineMediaIds.parsePlaylist(url))
    }
    @Test fun rejectArbitraryUrlsPrivateKeysFeedsAndMalformedIds() {
        for(url in listOf("http://vkvideo.ru/video-1_2", "https://vkvideo.ru.evil.test/video-1_2", "https://vkvideo.ru@127.0.0.1/video-1_2", "https://vkvideo.ru:8765/video-1_2", "https://127.0.0.1/video-1_2", "file:///video-1_2", "https://vkvideo.ru/video-1_2?access_key=private", "https://vkvideo.ru/video-1_2#fragment", "https://vkvideo.ru/video0_2", "https://vkvideo.ru/video-1_0", "https://vkvideo.ru/video9223372036854775808_2")) assertThrows(url,IllegalArgumentException::class.java) { OnlineMediaIds.parseLink(url) }
        for(url in listOf("https://vkvideo.ru/@channel", "https://vkvideo.ru/playlist/-1_-2", "https://vkvideo.ru/playlist/-1_2/season_1", "https://vkvideo.ru/video-1_2", "https://vkvideo.ru/video-1_2?pl=-1_2&pl=-1_3")) assertThrows(url,IllegalArgumentException::class.java) { OnlineMediaIds.parsePlaylist(url) }
    }
}
