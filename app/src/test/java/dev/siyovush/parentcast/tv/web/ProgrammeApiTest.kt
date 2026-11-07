package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgrammeApiTest {
    private val a="video000001";private val b="video000002"
    private fun TestScope.fixture(initial:QueueState=QueueState(listOf(QueueItem("a",a),QueueItem("b",b)),0)):Triple<ProgrammeApi,PlaybackCoordinator,Store> {
        val store=Store(initial)
        val player=object:PlaybackPlayer{override fun setListener(listener:PlaybackPlayer.Listener){};override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){};override fun play(){};override fun pause(){};override fun stop(){};override fun currentPositionMs()=0L;override fun release(){}}
        val source=object:YouTubeSource{override suspend fun resolve(videoId:String):ResolvedVideo=error("No playback in metadata API")}
        val c=PlaybackCoordinator(source,store,player,this,StandardTestDispatcher(testScheduler))
        return Triple(ProgrammeApi(c,object:CallDispatcher{override fun <T> call(block:()->T)=block()},{it=="a".repeat(64)}),c,store)
    }
    private fun body(action:String,version:Long=0)=JSONObject().put("action",action).put("queueVersion",version).put("idempotencyKey","test-key")
    @Test fun strictValidationRejectsMalformedTypesAndUnexpectedFieldsWithoutMutation()=runTest {
        val (api,c,store)=fixture();val baseline=store.state
        val malformed=listOf(
            body("REORDER_LOOSE").put("videoIds",JSONArray(listOf(a,a))),
            body("REORDER_LOOSE").put("videoIds",JSONArray(listOf(a,123))),
            body("FAVORITE_VIDEO").put("videoId",a).put("value","true"),
            body("FAVORITE_VIDEO").put("videoId",a).put("value",true).put("queueVersion",0.5),
            body("STOP_AFTER_CURRENT").put("value",true).put("filePath","/etc/passwd"),
            body("BOOKMARK_ADD").put("videoId",a).put("name","bad\nname").put("positionMs",100),
            body("CHILD_SCREEN").put("enabled",true).put("groupIds",JSONArray(listOf("g_"+"a".repeat(32))))
        )
        malformed.forEachIndexed { n,j -> assertEquals("payload $n",400,api.mutate(j,"test-key-$n","fp$n").code);assertEquals(baseline,store.state) }
        c.close()
    }
    @Test fun createCoverChildAndUndoPublishVersionedStateAndReplay()=runTest {
        val (api,c,store)=fixture()
        val create=body("CREATE_GROUP").put("name","Наша программа").put("videoIds",JSONArray(listOf(b,a)))
        assertEquals(200,api.mutate(create,"create-key","create").code)
        val group=store.state.groups.single();assertEquals(listOf(b,a),group.videoIds)
        assertEquals(200,api.mutate(create,"create-key","create").code);assertEquals(1,store.state.groups.size)
        assertEquals(409,api.mutate(create,"create-key","different").code)
        val cover=body("UPDATE_GROUP",store.state.version).put("groupId",group.id).put("coverImageId","b".repeat(64))
        assertEquals(404,api.mutate(cover,"cover-key","cover").code)
        cover.put("coverImageId","a".repeat(64));assertEquals(200,api.mutate(cover,"cover-valid","cover-valid").code)
        val child=body("CHILD_SCREEN",store.state.version).put("enabled",true).put("groupIds",JSONArray(listOf(group.id)))
        assertEquals(200,api.mutate(child,"child-key","child").code)
        val data=JSONObject(api.read().body).getJSONObject("programme")
        assertEquals(store.state.version,data.getLong("queueVersion"));assertEquals(group.id,data.getJSONObject("childScreen").getJSONArray("groupIds").getString(0))
        assertEquals("a".repeat(64),data.getJSONArray("groups").getJSONObject(0).getString("coverImageId"))
        val undo=body("UNDO",store.state.version).put("undoId",data.getJSONObject("undo").getString("id"))
        assertEquals(200,api.mutate(undo,"undo-key","undo").code);assertNull(store.state.groups.single().coverImageId)
        assertEquals(listOf(group.id),store.state.childScreen.groupIds)
        c.close()
    }
    @Test fun staleVersionAndCurrentRemovalAreHonestConflicts()=runTest {
        val (api,c,store)=fixture()
        assertEquals(409,api.mutate(body("STOP_AFTER_CURRENT",999).put("value",true),"stale-key","stale").code)
        c.play()
        val response=api.mutate(body("REMOVE_VIDEOS",store.state.version).put("videoIds",JSONArray(listOf(a))),"remove-key","remove")
        assertEquals(409,response.code);assertEquals("CURRENT_VIDEO_IN_USE",JSONObject(response.body).getString("error"))
        assertEquals(2,store.state.items.size);c.close()
    }
    @Test fun nextPreviewUsesGroupOrderAndOneShotReturnContextWithoutMutation()=runTest {
        val (api,c,store)=fixture();val saved=store.state;val coordinatorBefore=c.currentSnapshot()
        val third="video000003"
        val group=QueueGroup("g_"+"a".repeat(32),"Программа",listOf(a,third),QueueGroupSource.CUSTOM)
        val ordinary=QueueState(listOf(QueueItem("a",a),QueueItem("b",b),QueueItem("c",third)),0,
            positionMs=1200,intent=PlaybackIntent.PAUSE,groups=listOf(group),activeGroupId=group.id,version=7)
        val pending=ordinary.copy(playNext=NextVideo(b))
        val inFlight=pending.next()
        val removedReturnGroup=inFlight.copy(groups=emptyList())
        val cases=listOf(
            Triple(ordinary,third,group.id),
            Triple(pending,b,null),
            Triple(inFlight,third,group.id),
            Triple(removedReturnGroup,null,null)
        )
        cases.forEach { (state,nextId,nextGroup) ->
            val before=state.copy()
            val data=api.state(CoordinatorSnapshot(queueState=state))
            assertEquals(nextId,data.optStringOrNull("nextVideoId"))
            assertEquals(nextGroup,data.optStringOrNull("nextGroupId"))
            assertEquals(7L,data.getLong("queueVersion"))
            assertEquals(before,state);assertEquals(saved,store.state);assertEquals(coordinatorBefore,c.currentSnapshot())
        }
        assertEquals(b,api.state(CoordinatorSnapshot(queueState=pending)).getString("playNextVideoId"))
        assertTrue(api.state(CoordinatorSnapshot(queueState=inFlight)).isNull("playNextVideoId"))
        c.close()
    }
    @Test fun nextPreviewIsNullWhenStoppedAfterCurrentFinishedOrEmpty()=runTest {
        val (api,c,store)=fixture();val saved=store.state
        val pending=saved.copy(playNext=NextVideo(b))
        listOf(pending.copy(stopAfterCurrent=true),pending.copy(finished=true),saved.copy(currentIndex=1),QueueState.Empty).forEach { state ->
            val data=api.state(CoordinatorSnapshot(queueState=state))
            assertTrue(data.isNull("nextVideoId"));assertTrue(data.isNull("nextGroupId"));assertEquals(saved,store.state)
        }
        c.close()
    }
    @Test fun replacingOrClearingInFlightOneShotConflictsAndPreservesOriginalReturn()=runTest {
        val third="video000003"
        val group=QueueGroup("g_"+"b".repeat(32),"Возврат",listOf(a,third),QueueGroupSource.CUSTOM)
        val initial=QueueState(listOf(QueueItem("a",a),QueueItem("b",b),QueueItem("c",third)),0,
            intent=PlaybackIntent.PLAY,groups=listOf(group),activeGroupId=group.id,playNext=NextVideo(b),version=4).next()
        val (api,c,store)=fixture(initial)
        val before=c.currentSnapshot()
        listOf(a,null).forEachIndexed { index,id ->
            val response=api.mutate(body("PLAY_NEXT",initial.version).put("videoId",id ?: JSONObject.NULL),"change-next-$index","change-next-$index")
            assertEquals(409,response.code);assertEquals("PLAY_NEXT_IN_PROGRESS",JSONObject(response.body).getString("error"))
            assertEquals(initial,store.state);assertEquals(before,c.currentSnapshot())
        }
        val returned=QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(store.state)).next()
        assertEquals(third,returned.currentItem?.videoId);assertEquals(group.id,returned.activeGroupId);assertNull(returned.playNext)
        c.close()
    }
    @Test fun activeCurrentMembershipCannotBeMovedOrRemovedButCanBeCopied()=runTest {
        val third="video000003"
        val group=QueueGroup("g_"+"c".repeat(32),"Текущий",listOf(a,third),QueueGroupSource.CUSTOM)
        val destination=QueueGroup("g_"+"d".repeat(32),"Другой",listOf(b),QueueGroupSource.CUSTOM)
        for(intent in listOf(PlaybackIntent.PLAY,PlaybackIntent.PAUSE)) {
            val initial=QueueState(listOf(QueueItem("a",a),QueueItem("b",b),QueueItem("c",third)),0,
                positionMs=1200,intent=intent,groups=listOf(group,destination),activeGroupId=group.id)
            val (api,c,store)=fixture(initial);val before=c.currentSnapshot()
            for(mode in listOf("MOVE","REMOVE")) {
                val request=body("MEMBERSHIP").put("videoIds",JSONArray(listOf(a))).put("fromGroupId",group.id)
                    .put("toGroupId",if(mode=="MOVE")destination.id else JSONObject.NULL).put("mode",mode)
                val response=api.mutate(request,"membership-$mode","membership-$mode")
                assertEquals(409,response.code);assertEquals("CURRENT_VIDEO_IN_USE",JSONObject(response.body).getString("error"))
                assertEquals(initial,store.state);assertEquals(before,c.currentSnapshot())
            }
            val copy=body("MEMBERSHIP").put("videoIds",JSONArray(listOf(a))).put("fromGroupId",group.id).put("toGroupId",destination.id).put("mode","COPY")
            assertEquals(200,api.mutate(copy,"membership-copy","membership-copy").code)
            assertEquals(group.id,store.state.activeGroupId);assertEquals(a,store.state.currentItem?.videoId)
            assertEquals(1200L,store.state.positionMs);assertEquals(intent,store.state.intent)
            assertTrue(a in store.state.groups.last().videoIds)
            val stopped=initial.stop().applyProgramme(ProgrammeCommand.Membership(listOf(a),group.id,null,"REMOVE"))
            assertEquals(PlaybackIntent.STOP,stopped.intent);assertNull(stopped.activeGroupId)
            c.close()
        }
    }
    private fun JSONObject.optStringOrNull(key:String):String?=if(isNull(key))null else getString(key)
    private class Store(var state:QueueState):QueueStore{override fun read()=state;override fun write(state:QueueState){this.state=state}}
}
