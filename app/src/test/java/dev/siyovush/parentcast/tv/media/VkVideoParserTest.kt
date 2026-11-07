package dev.siyovush.parentcast.tv.media

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class VkVideoParserTest {
    private fun root(): ObjectNode = ObjectMapper().readTree("""{"id":"-227392256_456239129","extractor_key":"VK","title":"Test","duration":116,"is_live":false,"requested_formats":[{"format_id":"dash_sep-6","vcodec":"avc1.640028","acodec":"none","ext":"mp4","height":1080,"protocol":"https","url":"https://vkvd463.okcdn.ru/video.mp4"},{"format_id":"dash_sep-11","vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","protocol":"https","url":"https://vkvd463.okcdn.ru/audio.mp4"}]}""") as ObjectNode
    @Test fun completedAnonymousMetadataBecomesQualifiedH264AacStreams() {
        val video=VkVideoParser().parse(root(),"vk:-227392256_456239129")
        assertEquals("vk:-227392256_456239129",video.videoId)
        assertEquals(MediaProvider.VK,video.video.provider)
        assertEquals(MediaProvider.VK,video.audio!!.provider)
        assertFalse(video.video.headers.keys.any { it.equals("cookie",true) || it.equals("authorization",true) })
        assertEquals("https://vkvideo.ru",video.video.headers["Origin"])
    }
    @Test fun liveUnknownCompletionRestrictedAndDelegatedResultsAreRejected() {
        for(change in listOf<(ObjectNode)->Unit>({it.put("is_live",true)}, {it.remove("is_live")}, {it.put("duration",0)}, {it.put("availability","unlisted")}, {it.put("availability","private")}, {it.put("was_live",true)}, {it.put("has_drm",true)}, {it.put("id","-1_2")}, {it.put("extractor_key","Youtube")})) {
            val r=root();change(r);assertThrows(YouTubeSourceException::class.java) { VkVideoParser().parse(r,"vk:-227392256_456239129") }
        }
    }
    @Test fun providerUrlsAndPrivateDnsAreFenced() {
        for(url in listOf("https://127.0.0.1/v", "http://vkvd463.okcdn.ru/v", "https://user:pass@vkvd463.okcdn.ru/v", "https://vkvd463.okcdn.ru.evil.test/v", "https://vkvd463.okcdn.ru:8443/v", "https://example.org/v", "https://r1.googlevideo.com/v")) {
            assertFalse(url,VkMediaPolicy.allows(url.toHttpUrl()))
            val r=root();(r.path("requested_formats")[0] as ObjectNode).put("url",url)
            assertThrows(YouTubeSourceException::class.java) { VkVideoParser().parse(r,"vk:-227392256_456239129") }
        }
        for(ip in listOf("127.0.0.1","10.0.0.1","192.168.1.1","172.16.0.1","169.254.169.254","100.64.0.1","::1","fc00::1","fe80::1")) assertFalse(ip,VkMediaPolicy.publicAddress(InetAddress.getByName(ip)))
        assertTrue(VkMediaPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
    }
}
