package dev.siyovush.parentcast.tv.streaming

import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.youtube.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RangeTransportTest {
    private fun response(total:Long=8,start:Long=0,end:Long=0,etag:String?="\"one\"")=MockResponse().setResponseCode(206)
        .setHeader("Content-Range","bytes $start-$end/$total").setBody("a".repeat((end-start+1).toInt())).apply { if(etag!=null)setHeader("ETag",etag) }
    @Test fun exactRangesAndIfRangePreserveIdentity()=MockWebServer().use { server ->
        server.start();server.enqueue(response());server.enqueue(response(start=4,end=7))
        val transport=RangeTransport(DisabledYouTubeSession,OkHttpClient()){_,u->u.host==server.hostName}
        val spec=StreamSpec(server.url("/media").toString(),"video/mp4",emptyMap(),8,"format1")
        val identity=transport.probe(spec)
        transport.open(spec,4,4,identity).use { assertEquals(4,it.input.readBytes().size) }
        assertEquals("bytes=0-0",server.takeRequest().getHeader("Range"))
        val request=server.takeRequest();assertEquals("bytes=4-7",request.getHeader("Range"));assertEquals("\"one\"",request.getHeader("If-Range"))
    }
    @Test fun rejectsIgnoredRangeChangedRepresentationAndWeakIdentity()=MockWebServer().use { server ->
        server.start();val t=RangeTransport(DisabledYouTubeSession,OkHttpClient()){_,_->true}
        val spec=StreamSpec(server.url("/x").toString(),"video/mp4",emptyMap(),8,"format1")
        val old=RangeIdentity(8,"\"one\"",true,"format1")
        for((reply,code) in listOf(MockResponse().setBody("full") to "REPRESENTATION_CHANGED",response(etag="\"two\"") to "REPRESENTATION_CHANGED",response(start=1,end=1) to "INVALID_RANGE",response(etag=null) to "RANGE_IDENTITY_UNCONFIRMED",MockResponse().setResponseCode(403) to "URL_EXPIRED")){
            server.enqueue(reply);assertEquals(code,assertThrows(RangeFailure::class.java){t.open(spec,0,1,old)}.reason)
        }
        server.enqueue(response(etag=null));assertEquals("",t.probe(spec.copy(resumeIdentity="format1|revision=123")).validator)
    }
    @Test fun redirectsRecheckAllowlistAndVkNeverReceivesYoutubeCookies()=MockWebServer().use { server ->
        server.start();val session=object:YouTubeSession { override fun cookiesFor(url:HttpUrl)=listOf(Cookie.Builder().name("fixture").value("test-only").hostOnlyDomain(url.host).build()) }
        val t=RangeTransport(session,OkHttpClient.Builder().followRedirects(false).build()){_,u->u.host==server.hostName}
        val spec=StreamSpec(server.url("/x").toString(),"video/mp4",mapOf("Cookie" to "forbidden"),8,"fmt",MediaProvider.VK)
        server.enqueue(response());t.probe(spec);assertNull(server.takeRequest().getHeader("Cookie"))
        server.enqueue(response());t.probe(spec.copy(provider=MediaProvider.YOUTUBE));assertEquals("fixture=test-only",server.takeRequest().getHeader("Cookie"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://attacker.invalid/x"))
        assertEquals("UNSUPPORTED_MEDIA_HOST",assertThrows(RangeFailure::class.java){t.probe(spec)}.reason)
    }
    @Test fun foregroundCancelsOnlyBackgroundAndExplicitStopCancelsCurrent() {
        val client=OkHttpClient();fun call()=client.newCall(Request.Builder().url("https://example.invalid").build())
        val p=StreamPriority();val bg=call();val audio=call();val video=call()
        p.register("a",bg,false);p.register("a",audio,true);p.enterForeground();p.register("a",video,true)
        assertTrue(bg.isCanceled());assertFalse(audio.isCanceled());assertFalse(video.isCanceled());assertFalse(p.backgroundAllowed())
        p.leaveForeground();assertTrue(p.backgroundAllowed());p.cancelPlayback("a");assertTrue(audio.isCanceled());assertTrue(video.isCanceled())
    }
}
