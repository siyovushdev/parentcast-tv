package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineVideoApiTest {
    @get:Rule val tmp=TemporaryFolder()
    private val id="jNQXAC9IVRw"
    private val quality="a".repeat(64)
    private class Player:PlaybackPlayer {
        var plays=0
        override fun setListener(listener:PlaybackPlayer.Listener){}
        override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){if(playWhenReady)plays++}
        override fun play(){plays++}
        override fun pause(){}
        override fun stop(){}
        override fun currentPositionMs()=0L
        override fun release(){}
    }
    private inner class Fixture {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dispatcher=object:CallDispatcher {private val lock=Any();override fun <T> call(block:()->T):T=synchronized(lock){block()}}
        val player=Player()
        val store=AtomicQueueStore(tmp.newFile())
        val coordinator:PlaybackCoordinator
        val journal=java.io.File(tmp.root,"online-${java.util.UUID.randomUUID()}.json")
        var starts=0
        var behavior:suspend ()->Unit={}
        init {store.write(QueueState.Empty);coordinator=PlaybackCoordinator(object:YouTubeSource{override suspend fun resolve(videoId:String):ResolvedVideo=throw CancellationException()},store,player,scope);coordinator.start()}
        fun api()=OnlineVideoApi(coordinator,dispatcher,scope,journal,{videoId->JSONObject().put("videoId",videoId).put("title","A <script>name</script>").put("durationSeconds",20)
            .put("qualities",JSONArray().put(JSONObject().put("id",quality).put("height",720).put("sizeBytes",12345)))},
            {_,_,_,_,_->starts++;behavior()},{true})
        fun preview(api:OnlineVideoApi):String {
            val response=api.mutate("/api/video/preview","owner",JSONObject().put("url","https://youtu.be/$id"),"preview-key","preview-fingerprint")
            assertEquals(202,response.code);val previewId=JSONObject(response.body).getJSONObject("preview").getString("previewId")
            val deadline=System.nanoTime()+5_000_000_000L
            while(System.nanoTime()<deadline){val state=JSONObject(api.read("/api/video/preview/$previewId","owner")!!.body).getJSONObject("preview");if(state.getString("status")=="COMPLETE")return previewId;Thread.sleep(5)}
            throw AssertionError("Preview did not finish")
        }
        fun body(previewId:String,action:String="WATCH_SAVE")=JSONObject().put("previewId",previewId).put("action",action).put("qualityId",quality)
            .put("queueVersion",coordinator.currentSnapshot().queueState.version).put("storageId","internal").put("storageVersion",0)
        fun close(){scope.cancel()}
    }
    @Test fun previewIsOwnerBoundAndNeverChangesQueue(){
        val f=Fixture();try {
            val api=f.api();val p=f.preview(api)
            assertTrue(f.coordinator.currentSnapshot().queueState.items.isEmpty());assertEquals(0,f.starts)
            assertEquals(404,api.read("/api/video/preview/$p","other")!!.code)
            assertFalse(api.containsVideo("other",id));assertTrue(api.containsVideo("owner",id))
            assertEquals(400,api.mutate("/api/video/preview","owner",JSONObject().put("url","http://127.0.0.1/secret"),"invalid-key","invalid-fingerprint").code)
            assertEquals(200,api.mutate("/api/video/cancel","owner",JSONObject().put("previewId",p),"cancel-key","cancel-fingerprint").code)
            assertFalse(api.containsVideo("owner",id))
        }finally{f.close()}
    }
    @Test fun watchReplayAfterStopAndApiRestartDoesNotPlayAgain(){
        val f=Fixture();try{
            val api=f.api();val body=f.body(f.preview(api))
            assertEquals(200,api.mutate("/api/video/apply","owner",body,"watch-key","watch-fingerprint").code)
            assertEquals(PlaybackIntent.PLAY,f.coordinator.currentSnapshot().queueState.intent)
            f.coordinator.stop();val version=f.coordinator.currentSnapshot().queueState.version
            val replay=f.api().mutate("/api/video/apply","owner",body,"watch-key","watch-fingerprint")
            assertEquals(200,replay.code);assertTrue(JSONObject(replay.body).getBoolean("replayed"))
            assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent)
            assertEquals(version,f.coordinator.currentSnapshot().queueState.version);assertEquals(1,f.starts)
            assertEquals(409,api.mutate("/api/video/apply","owner",body,"watch-key","changed").code)
        }finally{f.close()}
    }
    @Test fun concurrentStopDuringPreparationPreventsDelayedPlay(){
        val f=Fixture();try{
            val api=f.api();val body=f.body(f.preview(api));f.behavior={f.dispatcher.call {f.coordinator.stop()}}
            val response=api.mutate("/api/video/apply","owner",body,"stopped-key","stopped-fingerprint")
            assertEquals(200,response.code);assertEquals("PLAYBACK_CHANGED",JSONObject(response.body).getString("error"))
            assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent);assertEquals(0,f.player.plays)
        }finally{f.close()}
    }
    @Test fun preparationFailureKeepsSavedVideoAndRetriesWithoutDuplicate(){
        val f=Fixture();try{
            val api=f.api();val body=f.body(f.preview(api),"DOWNLOAD_LATER");f.behavior={throw IllegalStateException("NO_SPACE")}
            val failed=api.mutate("/api/video/apply","owner",body,"download-key","download-fingerprint")
            assertEquals(503,failed.code);assertEquals("SAVED",JSONObject(failed.body).getString("stage"))
            assertEquals(1,f.coordinator.currentSnapshot().queueState.items.size)
            f.behavior={};assertEquals(200,f.api().mutate("/api/video/apply","owner",body,"download-key","download-fingerprint").code)
            assertEquals(1,f.coordinator.currentSnapshot().queueState.items.size);assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent)
        }finally{f.close()}
    }
    @Test fun staleQueueAndBadQualityNeverStartDownload(){
        val f=Fixture();try{
            val api=f.api();val body=f.body(f.preview(api));f.coordinator.add("aqz-KE-bpKQ")
            assertEquals(409,api.mutate("/api/video/apply","owner",body,"stale-key","stale-fingerprint").code)
            val bad=f.body(f.preview(api)).put("qualityId","not-preview-choice")
            assertEquals(400,api.mutate("/api/video/apply","owner",bad,"bad-quality-key","bad-quality-fingerprint").code)
            assertEquals(0,f.starts);assertEquals(listOf("aqz-KE-bpKQ"),f.coordinator.currentSnapshot().queueState.items.map{it.videoId})
        }finally{f.close()}
    }
}
