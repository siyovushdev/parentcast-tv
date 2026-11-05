package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SeekApiTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun authenticatedSeekIsIdempotentValidatesInputAndCannotTargetAnotherClip()=runTest {
        var position=12_000L;val seekCalls=mutableListOf<Long>()
        val player=object:PlaybackPlayer {
            override fun setListener(listener:PlaybackPlayer.Listener){};override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){position=positionMs}
            override fun play(){};override fun pause(){};override fun stop(){};override fun release(){}
            override fun currentPositionMs()=position;override fun durationMs()=120_000L;override fun isSeekable()=true;override fun seekTo(positionMs:Long){seekCalls+=positionMs;position=positionMs}
        }
        val store=AtomicQueueStore(File(temp.root,"queue.json"));store.write(QueueState(listOf(QueueItem("one","video000001"),QueueItem("two","video000002")),0,12_000,PlaybackIntent.PAUSE))
        val c=PlaybackCoordinator(object:YouTubeSource{override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Fixture",120,StreamSpec("https://media.invalid/v.mp4","video/mp4",emptyMap()),null,"fixture")},store,player,this,StandardTestDispatcher(testScheduler))
        c.start();advanceUntilIdle()
        val tokens=AtomicTokenStore(File(temp.root,"tokens.json"))
        val pin=PinSecurity.hashPin("827391")
        val settings=object:PinSettings{override fun hasPin()=true;override fun pinRecord()=pin;override fun savePinRecord(record:String){}}
        val api=ApiController(PairingManager(settings,tokens),tokens,AtomicIdempotencyStore(File(temp.root,"commands.json")),c,object:CallDispatcher{override fun <T> call(block:()->T)=block()},RequestSecurity(setOf("127.0.0.1"),8765),{""},mediaDuration={120L})
        val headers=mapOf("host" to "127.0.0.1:8765","origin" to "http://127.0.0.1:8765","x-parentcast-client" to "m1")
        val pair=api.handle(ApiRequest("POST","/api/pair",headers,JSONObject().put("pin","827391").put("label","fixture").toString(),"test"))
        val auth=headers+("authorization" to "Bearer "+JSONObject(pair.body).getString("token"))
        val state=JSONObject(api.stateBody());assertEquals(120L,state.getJSONArray("items").getJSONObject(0).getLong("durationSeconds"));assertEquals(120_000L,state.getLong("durationMs"));assertTrue(state.getBoolean("seekable"))
        val body=JSONObject().put("queueItemId","one").put("queueVersion",state.getLong("queueVersion")).put("playbackGeneration",state.getLong("playbackGeneration")).put("positionMs",50_000).put("idempotencyKey","seek-repeat-1")
        fun send(h:Map<String,String> =auth)=api.handle(ApiRequest("POST","/api/playback/seek",h,body.toString(),"test"))
        assertEquals(401,send(headers).code);assertEquals(421,send(auth+("host" to "evil.test:8765")).code);assertEquals(403,send(auth+("origin" to "http://evil.test")).code)
        val first=send();assertEquals(200,first.code);assertEquals(first.body,send().body);assertEquals(listOf(50_000L),seekCalls);assertEquals(PlaybackIntent.PAUSE,c.currentSnapshot().queueState.intent)
        body.put("idempotencyKey","invalid-float").put("positionMs",1.5);assertEquals(400,send().code)
        body.put("idempotencyKey","stale-after-next").put("positionMs",1000);c.next();advanceUntilIdle();assertEquals(409,send().code);assertEquals(listOf(50_000L),seekCalls)
        c.close()
    }
}
