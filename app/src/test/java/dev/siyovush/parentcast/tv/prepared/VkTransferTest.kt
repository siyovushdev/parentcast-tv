package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VkTransferTest {
    @get:Rule val temp=TemporaryFolder()
    private fun spec()=StreamSpec("https://vkvd463.okcdn.ru/media", "video/mp4",
        mapOf("Cookie" to "must-not-pass", "Authorization" to "must-not-pass"),3,"vk-format",MediaProvider.VK)
    @Test fun youtubeSessionAndUntrustedHeadersNeverReachVkTransfer()=runBlocking {
        var requests=0
        val session=object: YouTubeSession { override fun cookiesFor(url: HttpUrl): List<Cookie> = error("Must not open YouTube session") }
        val transport=OkHttpClient.Builder().addInterceptor { chain ->
            requests++;val request=chain.request();assertNull(request.header("Cookie"));assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag","\"vk-one\"").body(byteArrayOf(1,2,3).toResponseBody()).build()
        }.build()
        val file=File(temp.root,"vk:-1_2.video.mp4")
        HttpTrackDownloader(session,vkTransport=transport).download(spec(),file,100) { _,_-> }
        assertArrayEquals(byteArrayOf(1,2,3),file.readBytes());assertEquals(1,requests)
    }
    @Test fun redirectOutsideProviderIsRejectedBeforeAnotherRequest() {
        var requests=0
        val transport=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requests++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location","https://127.0.0.1/private").body(byteArrayOf().toResponseBody()).build()
        }.build()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            HttpTrackDownloader(DisabledYouTubeSession,vkTransport=transport).download(spec(),File(temp.root,"vk:-1_2.video.mp4"),100) { _,_-> }
        } }
        assertEquals(1,requests)
    }
}
