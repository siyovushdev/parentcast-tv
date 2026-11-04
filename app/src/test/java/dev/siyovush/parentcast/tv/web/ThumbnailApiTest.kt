package dev.siyovush.parentcast.tv.web

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailApiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    @Test fun readIsAsynchronousDeduplicatedAndSurvivesAServiceRestart() = runTest {
        var generated=0
        val dir=temporary.newFolder()
        val api=ThumbnailApi(dir,this,{ generated++;delay(100);jpeg },dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals(202,api.read("video000001",true).code)
        assertEquals(202,api.read("video000001",true).code)
        assertEquals(0,generated)
        advanceUntilIdle()
        val ready=api.read("video000001",true)
        assertEquals(200,ready.code)
        assertEquals(1,generated)
        assertTrue(JSONObject(ready.body).getString("image").startsWith("data:image/jpeg;base64,"))
        api.close()
        val restored=ThumbnailApi(dir,this,{error("must use derived cache")},dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals(ready.body,restored.read("video000001",true).body)
        restored.close()
    }
    @Test fun unknownIdsTraversalAndCachedUnauthorizedImagesCannotBeRead() = runTest {
        var called=false
        val dir=temporary.newFolder()
        val api=ThumbnailApi(dir,this,{called=true;jpeg},dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals(400,api.read("../private",true).code)
        assertEquals(404,api.read("video000001",false).code)
        File(dir,"video000001.jpg").writeBytes(jpeg)
        assertEquals(404,api.read("video000001",false).code)
        advanceUntilIdle();assertFalse(called);api.close()
    }
    @Test fun oneWorkerBoundsQueuedJobsAndBadImagesDoNotBecomeReady() = runTest {
        var active=0;var peak=0
        val api=ThumbnailApi(temporary.newFolder(),this,{ active++;peak=maxOf(peak,active);delay(100);active--;byteArrayOf(1) },dispatcher=StandardTestDispatcher(testScheduler))
        repeat(20){assertEquals(202,api.read("video%06d".format(it),true).code)}
        advanceUntilIdle()
        assertEquals(1,peak)
        assertEquals(404,api.read("video000001",true).code)
        api.close()
    }
    @Test fun failedImagesCanBeRetriedAndTheCacheHasABudget() = runTest {
        var now=100_000L;var succeed=false
        val dir=temporary.newFolder()
        val api=ThumbnailApi(dir,this,{if(succeed)jpeg else null},{now},maxBytes=jpeg.size.toLong(),dispatcher=StandardTestDispatcher(testScheduler))
        api.read("video000001",true);advanceUntilIdle()
        assertEquals(404,api.read("video000001",true).code)
        now+=60_001;succeed=true
        assertEquals(202,api.read("video000001",true).code);advanceUntilIdle()
        assertEquals(200,api.read("video000001",true).code)
        now+=1;api.read("video000002",true);advanceUntilIdle()
        assertTrue(dir.listFiles()!!.sumOf{it.length()}<=jpeg.size)
        assertEquals(200,api.read("video000002",true).code)
        api.close()
    }
    @Test fun imageFetchesAllowOnlyHttpsImageCdns() {
        listOf("https://i.ytimg.com/vi/video000001/hqdefault.jpg","https://sun9-10.userapi.com/a.jpg","https://i.mycdn.me/a.jpg").forEach { assertTrue(it,ThumbnailPolicy.allows(it.toHttpUrl())) }
        listOf("http://i.ytimg.com/a.jpg","https://i.ytimg.com.evil.test/a.jpg","https://userapi.com.evil.test/a.jpg","https://127.0.0.1/a.jpg","https://192.168.1.16/a.jpg","https://user:pass@i.ytimg.com/a.jpg","https://i.ytimg.com:8443/a.jpg","https://youtube.com/a.jpg").forEach { assertFalse(it,ThumbnailPolicy.allows(it.toHttpUrl())) }
    }
}
