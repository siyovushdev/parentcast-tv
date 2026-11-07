package dev.siyovush.parentcast.tv.streaming

import androidx.media3.datasource.DataSpec
import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.youtube.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class OnlineHttpSourceTest {
    private fun data(position:Long=0,length:Long=-1)=DataSpec.Builder().setUri("parentcast-online://video").setPosition(position).apply {if(length>=0)setLength(length)}.build()
    private fun spec(server:MockWebServer)=StreamSpec(server.url("/media").toString(),"video/mp4",mapOf("Cookie" to "must-not-forward"),provider=MediaProvider.VK)
    private fun source(server:MockWebServer,resolve:(Boolean)->StreamSpec={spec(server)},session:YouTubeSession=DisabledYouTubeSession)=OnlineHttpSource(session,resolve,OkHttpClient.Builder().followRedirects(false).build(),{_,url->url.host==server.hostName})
    @Test fun deliversPrefixWithoutWaitingForWholeFileAndDoesNotExposeSignedUrl()=MockWebServer().use {server->
        server.start();server.enqueue(MockResponse().setBody("x".repeat(65536)).throttleBody(1024,1,TimeUnit.SECONDS))
        val source=source(server);val start=System.nanoTime()
        assertEquals(65536L,source.open(data()));assertEquals(128,source.read(ByteArray(128),0,128))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500)
        assertEquals("parentcast-online://video",source.uri.toString());source.close()
    }
    @Test fun seeksWithExactRangeAndRejectsIgnoredOrWrongRanges()=MockWebServer().use {server->
        server.start();server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 5-7/10").setBody("567"))
        val source=source(server);assertEquals(3L,source.open(data(5,3)));val bytes=ByteArray(9)
        assertEquals(3,source.read(bytes,0,9));assertEquals("567",bytes.copyOf(3).decodeToString());assertEquals(-1,source.read(bytes,0,9));source.close()
        assertEquals("bytes=5-7",server.takeRequest().getHeader("Range"))
        server.enqueue(MockResponse().setBody("0123456789"));assertEquals("RANGE_UNSUPPORTED",assertThrows(RangeFailure::class.java){source.open(data(5))}.reason)
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 4-7/10").setBody("4567"));assertEquals("INVALID_RANGE",assertThrows(RangeFailure::class.java){source.open(data(5,3))}.reason)
    }
    @Test fun expiredUrlRefreshesOnceAndEveryRedirectIsChecked()=MockWebServer().use {server->
        server.start();server.enqueue(MockResponse().setResponseCode(403));server.enqueue(MockResponse().setBody("ok"))
        val refresh=mutableListOf<Boolean>();val source=source(server,{force->refresh+=force;spec(server).copy(url=server.url(if(force)"/fresh" else "/old").toString())})
        assertEquals(2L,source.open(data()));source.close();assertEquals(listOf(false,true),refresh)
        assertEquals("/old",server.takeRequest().path);assertEquals("/fresh",server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://127.0.0.2/private"))
        assertEquals("UNSUPPORTED_MEDIA_HOST",assertThrows(RangeFailure::class.java){source.open(data())}.reason)
        server.takeRequest();assertEquals(3,server.requestCount)
    }
    @Test fun vkNeverReceivesYoutubeOrExtractorCookiesButYoutubeGetsScopedSession()=MockWebServer().use {server->
        server.start();val session=object:YouTubeSession {override fun cookiesFor(url:HttpUrl)=listOf(Cookie.Builder().name("fixture").value("test-only").hostOnlyDomain(url.host).build())}
        for(provider in listOf(MediaProvider.VK,MediaProvider.YOUTUBE)) {
            server.enqueue(MockResponse().setBody("ok"));val s=source(server,{spec(server).copy(provider=provider)},session);s.open(data());s.close()
            val request=server.takeRequest();assertEquals(if(provider==MediaProvider.VK)null else "fixture=test-only",request.getHeader("Cookie"))
        }
    }
    @Test fun closeCancelsBlockedNetworkOpen()=MockWebServer().use {server->
        server.start();server.enqueue(MockResponse().setHeadersDelay(3,TimeUnit.SECONDS).setBody("slow"))
        val s=source(server);val executor=Executors.newSingleThreadExecutor()
        try {val job=executor.submit<String>{try{s.open(data());"unexpected"}catch(e:RangeFailure){e.reason}}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));s.close()
            assertEquals("TRANSFER_CANCELLED",job.get(2,TimeUnit.SECONDS))
        }finally{s.close();executor.shutdownNow()}
    }
    @Test fun productionPoliciesRejectPrivateAndUnapprovedUrls() {
        val vk=StreamSpec("unused","video/mp4",emptyMap(),provider=MediaProvider.VK)
        for(url in listOf("http://a.okcdn.ru/x","https://127.0.0.1/x","https://okcdn.ru.evil.invalid/x","https://a.okcdn.ru:8443/x","https://user:pw@a.okcdn.ru/x"))assertFalse(OnlineHttpSource.allowed(vk,url.toHttpUrl()))
        assertTrue(OnlineHttpSource.allowed(vk,"https://a.okcdn.ru/x".toHttpUrl()))
        assertFalse(OnlineHttpSource.allowed(vk.copy(provider=MediaProvider.YOUTUBE),"https://a.okcdn.ru/x".toHttpUrl()))
        assertTrue(OnlineHttpSource.allowed(vk.copy(provider=MediaProvider.YOUTUBE),"https://rr1.googlevideo.com/x".toHttpUrl()))
    }
}
