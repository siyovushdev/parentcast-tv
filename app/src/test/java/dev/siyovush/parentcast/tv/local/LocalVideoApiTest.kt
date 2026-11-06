package dev.siyovush.parentcast.tv.local

import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.web.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.*
import org.junit.rules.TemporaryFolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.util.UUID
import java.util.concurrent.*

class LocalVideoApiTest {
    @get:Rule val temp=TemporaryFolder()
    private val fixtures=mutableListOf<Fixture>()
    @After fun close() { fixtures.forEach { it.local.close();it.coordinator.close();it.scope.cancel() } }
    private inner class Fixture(val limit:Long=LIBRARY_LIMIT, validator: (File)->LocalMediaInfo={LocalMediaInfo(10,"video/mp4")},usb:UsbFiles?=null,
                              storageProvider:(()->List<MediaVolume>)?=null) {
        val root=temp.newFolder();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val store=PreparedStore(File(root,"media"),storageProvider)
        val forbidden=object:YouTubeSource{override suspend fun resolve(videoId:String):ResolvedVideo=error("No YouTube for local media")}
        val library=PreparedLibrary(store,forbidden,TrackDownloader {_,_,_,_->error("No network")},scope,{_,_,_->},limit=limit)
        var prepares=0;var stops=0
        val player=object:PlaybackPlayer {
            override fun setListener(listener:PlaybackPlayer.Listener)=Unit
            override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean) {assertTrue(video.video.url.startsWith("file:"));prepares++}
            override fun play()=Unit;override fun pause()=Unit;override fun stop(){stops++};override fun currentPositionMs()=0L;override fun release()=Unit
        }
        val coordinator=PlaybackCoordinator(forbidden,AtomicQueueStore(File(root,"queue.json")),player,scope,Dispatchers.Unconfined,authorizationGate={false},localSource=object:YouTubeSource{override suspend fun resolve(videoId:String)=library.localOrWait(videoId,true)!!})
        val dispatcher=object:CallDispatcher{override fun<T> call(block:()->T)=block()}
        val jobs=File(root,"jobs.json")
        val local=LocalVideoApi(library,coordinator,dispatcher,jobs,scope,validator,usb)
        val tokens=AtomicTokenStore(File(root,"tokens.json"));val issued=tokens.create("synthetic")
        val pin=object:PinSettings{override fun hasPin()=true;override fun pinRecord()="";override fun savePinRecord(record:String)=Unit}
        val controller=ApiController(PairingManager(pin,tokens),tokens,AtomicIdempotencyStore(File(root,"commands.json")),coordinator,dispatcher,RequestSecurity(setOf("127.0.0.1"),8765),{""},localMedia=local,prepared=PreparedApi(library,coordinator,dispatcher),mediaTitle=library::mediaTitle)
        val headers=mapOf("host" to "127.0.0.1:8765","origin" to "http://127.0.0.1:8765","referer" to "http://127.0.0.1:8765/","x-parentcast-client" to "m1","authorization" to "Bearer ${issued.second}")
        init {coordinator.start();fixtures+=this}
        fun post(path:String,j:JSONObject):ApiResponse {if(!j.has("idempotencyKey"))j.put("idempotencyKey",UUID.randomUUID().toString());return controller.handle(ApiRequest("POST",path,headers,j.toString(),"fixture"))}
        fun start(size:Long=8,name:String="<img src=x>.mp4"):JSONObject {val r=post("/api/local/start",JSONObject().put("name",name).put("size",size));assertEquals(201,r.code);return JSONObject(r.body)}
        fun upload(task:JSONObject,input:InputStream=ByteArrayInputStream(ByteArray(task.getLong("size").toInt()) {7}))=controller.upload(ApiRequest("POST","/api/local/upload/"+task.getString("id"),headers+("content-type" to "application/octet-stream"),"","fixture"),task.getLong("size"),input)
    }
    @Test fun importPersistsOfflineWithoutAddingOrPlayingAndCanBeReused() {
        val f=Fixture();val before=f.coordinator.currentSnapshot().queueState;val task=f.start()
        assertEquals(201,f.upload(task).code);assertEquals(before,f.coordinator.currentSnapshot().queueState);assertEquals(0,f.prepares)
        val e=f.library.localEntries().single();assertEquals("<img src=x>.mp4",e.title);assertTrue(f.store.verify(e))
        assertEquals(200,f.upload(task,object:InputStream(){override fun read():Int=error("Replay must not reimport")}).code)
        val restored=PreparedLibrary(PreparedStore(f.store.directory),f.forbidden,TrackDownloader {_,_,_,_->error("network")},f.scope,{_,_,_->})
        assertTrue(runBlocking {restored.localOrWait(e.id,true)!!.video.url.startsWith("file:")})
        val body=JSONObject().put("videoIds",JSONArray().put(e.id)).put("queueVersion",before.version)
        val added=f.post("/api/local/add",body);assertEquals(200,added.code);assertEquals(added,f.post("/api/local/add",body));assertEquals(1,f.coordinator.currentSnapshot().queueState.items.size)
        assertTrue(f.coordinator.currentSnapshot().queueState.currentItem!!.local);assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent)
        f.coordinator.play();assertEquals(1,f.prepares);assertTrue(f.coordinator.currentSnapshot().localPlayback)
    }
    @Test fun incompleteAndInvalidMediaNeverBecomeReady() {
        for(invalid in listOf(false,true)) {
            val f=Fixture(validator={if(invalid)error("UNSUPPORTED_MEDIA") else LocalMediaInfo(1,"video/mp4")});val task=f.start(8)
            assertEquals(422,f.upload(task,ByteArrayInputStream(ByteArray(if(invalid)8 else 3))).code)
            assertTrue(f.library.localEntries().isEmpty());assertEquals(0L,f.store.usedBytes())
        }
    }
    @Test fun localUploadKeepsItsSelectedDestinationWhenAnotherParentChangesStorage() = runBlocking {
        val card=temp.newFolder();var mounted=true
        val f=Fixture(storageProvider={listOf(MediaVolume("sd:TEST_CARD","Test card",card.takeIf { mounted },true,mounted,mounted))})
        f.library.selectStorage("sd:TEST_CARD",f.library.storage.version(),StorageCommand("select-card-fixture","card"))
        val task=f.start()
        val mediaId=task.getString("mediaId")
        assertEquals("sd:TEST_CARD",f.library.mediaStorageId(mediaId))
        f.library.selectStorage(INTERNAL_STORAGE,f.library.storage.version(),StorageCommand("select-internal-fixture","internal"))
        assertEquals(201,f.upload(task).code)
        assertEquals(card.canonicalFile,f.store.track(mediaId).canonicalFile.parentFile)
        assertFalse(File(f.store.directory,"$mediaId.video.mp4").exists())
        assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent)

        mounted=false
        val files=JSONObject(f.controller.handle(ApiRequest("GET","/api/local",f.headers,"","fixture")).body).getJSONArray("files")
        assertEquals(1,files.length())
        assertEquals(false,files.getJSONObject(0).getBoolean("available"))
        assertEquals("STORAGE_UNAVAILABLE",files.getJSONObject(0).getString("preparationStatus"))
        assertEquals(PreparationStatus.READY,f.library.snapshot().entries.getValue(mediaId).status)
        mounted=true
        assertEquals("READY",f.library.mediaReadiness(mediaId))
        assertTrue(f.store.verify(f.library.localEntries().single()))
    }
    @Test fun authHostOriginLengthAndTypeAreCheckedBeforeMediaBody() {
        val f=Fixture();val id=UUID.randomUUID().toString();val trap=object:InputStream(){override fun read():Int=error("Unauthorized body read")}
        val base=f.headers+("content-type" to "application/octet-stream")
        for((headers,status) in listOf((base-"authorization") to 401,(base+("host" to "evil.invalid:8765")) to 421,(base+("origin" to "https://evil.invalid")) to 403,(base-"x-parentcast-client") to 403,(base+("content-type" to "text/html")) to 415)) {
            assertEquals(status,f.controller.upload(ApiRequest("POST","/api/local/upload/$id",headers,"","fixture"),8,trap).code)
        }
        assertEquals(413,f.controller.upload(ApiRequest("POST","/api/local/upload/$id",base,"","fixture"),EXTERNAL_LIBRARY_LIMIT+1,trap).code)
        assertEquals(413,f.controller.upload(ApiRequest("POST","/api/local/upload/$id",base+("transfer-encoding" to "chunked"),"","fixture"),8,trap).code)
        val task=f.start();assertEquals(400,f.controller.upload(ApiRequest("POST","/api/local/upload/"+task.getString("id"),base,"","fixture"),9,trap).code)
    }
    @Test fun absentCardForQueuedImportDoesNotDisableLocalLibraryAfterRestart() = runBlocking {
        val card=temp.newFolder();var mounted=true
        val f=Fixture(storageProvider={listOf(MediaVolume("sd:RESTART","Test card",card.takeIf { mounted },true,mounted,mounted))})
        f.library.selectStorage("sd:RESTART",f.library.storage.version(),StorageCommand("restart-card-select","card"))
        val waiting=f.start()
        f.library.selectStorage(INTERNAL_STORAGE,f.library.storage.version(),StorageCommand("restart-internal-select","internal"))
        val ready=f.start();assertEquals(201,f.upload(ready).code)
        mounted=false
        val restored=LocalVideoApi(f.library,f.coordinator,f.dispatcher,f.jobs,f.scope,{LocalMediaInfo(10,"video/mp4")})
        try {
            val response=restored.read("/api/local",f.issued.first.deviceId)!!
            assertEquals(200,response.code)
            val json=JSONObject(response.body)
            assertEquals(1,json.getJSONArray("files").length())
            assertEquals(2,json.getJSONArray("jobs").length())
            assertTrue((0 until json.getJSONArray("jobs").length()).any { json.getJSONArray("jobs").getJSONObject(it).getString("id")==waiting.getString("id") })
            assertTrue(f.store.verify(f.library.localEntries().single()))
        } finally { restored.close() }
    }
    @Test fun totalQuotaAndCacheCleanupProtectImportedVideos() = runBlocking {
        val f=Fixture(10);val first=f.start(8);assertEquals(201,f.upload(first).code)
        val second=f.start(3);val error=f.upload(second);assertEquals(422,error.code);assertEquals("NO_SPACE",JSONObject(error.body).getString("error"))
        assertEquals(8L,f.store.usedBytes());assertEquals(1,f.library.localEntries().size)
        f.library.syncQueue(f.coordinator.currentSnapshot().queueState)
        assertEquals(0,f.library.cleanup());f.library.deleteMedia();while(f.library.snapshot().deleting.isNotEmpty())yield()
        assertEquals(1,f.library.localEntries().size);assertEquals(8L,f.store.usedBytes())
    }
    @Test fun cancelClosesActiveInputAndPreservesOtherPrivateData() {
        val f=Fixture();val sentinel=File(f.root,"parent-auth.xml").apply {writeText("synthetic auth sentinel")};val task=f.start()
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val input=object:InputStream(){override fun read():Int {entered.countDown();released.await(5,TimeUnit.SECONDS);throw IOException("closed")};override fun close(){released.countDown()}}
        val pool=Executors.newSingleThreadExecutor()
        try {
            val work=pool.submit<ApiResponse>{f.upload(task,input)};assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertEquals(200,f.post("/api/local/cancel",JSONObject().put("jobId",task.getString("id"))).code)
            work.get(5,TimeUnit.SECONDS);assertTrue(f.library.localEntries().isEmpty());assertEquals(0L,f.store.usedBytes());assertEquals("synthetic auth sentinel",sentinel.readText())
        } finally {pool.shutdownNow()}
    }
    @Test fun reorderIsAtomicVersionedReplaySafeAndDoesNotTouchCurrentPlayback() {
        val f=Fixture();repeat(4){f.coordinator.add("video00000${it+1}")}
        val before=f.coordinator.currentSnapshot();val pending=before.queueState.pendingItems.reversed().map {it.queueItemId};val stops=f.stops
        val body=JSONObject().put("queueItemIds",JSONArray(pending)).put("queueVersion",before.queueState.version)
        val result=f.post("/api/queue/reorder",body);assertEquals(200,result.code)
        assertEquals(before.queueState.currentItem,f.coordinator.currentSnapshot().queueState.currentItem);assertEquals(pending,f.coordinator.currentSnapshot().queueState.pendingItems.map {it.queueItemId});assertEquals(stops,f.stops)
        f.coordinator.add("video000005");val after=f.coordinator.currentSnapshot().queueState
        assertEquals(result,f.post("/api/queue/reorder",body));assertEquals(after,f.coordinator.currentSnapshot().queueState)
        val stale=JSONObject().put("queueItemIds",JSONArray(pending)).put("queueVersion",before.queueState.version)
        assertEquals(409,f.post("/api/queue/reorder",stale).code)
        val bad=JSONObject().put("queueItemIds",JSONArray().put(after.currentItem!!.queueItemId)).put("queueVersion",after.version)
        assertEquals(400,f.post("/api/queue/reorder",bad).code)
    }
    @Test fun usbCopiesOnlySelectedFilesAndReportsRemovalWithoutChangingQueue() = runBlocking {
        for(removed in listOf(false,true)) {
            var opened=0
            val usb=object:UsbFiles {
                override fun connected()=true
                override fun list(folder:String)=listOf(UsbItem("one","USB test.mp4",8,false))
                override fun capture(key:String):UsbTransfer {require(key=="one");return UsbTransfer(list("root").single()) {opened++;if(removed)throw IOException("removed");ByteArrayInputStream(ByteArray(8))}}
            }
            val f=Fixture(usb=usb);val before=f.coordinator.currentSnapshot().queueState
            assertNotNull(f.local.read("/api/usb",f.issued.first.deviceId));assertEquals(0,opened)
            assertEquals(202,f.post("/api/usb/import",JSONObject().put("keys",JSONArray().put("one"))).code)
            withTimeout(5000) {while(true) {val jobs=JSONObject(f.local.read("/api/local",f.issued.first.deviceId)!!.body).getJSONArray("jobs");val status=jobs.getJSONObject(0).getString("status");if(status in setOf("READY","ERROR"))break;delay(10)}}
            assertEquals(1,opened);assertEquals(before,f.coordinator.currentSnapshot().queueState)
            assertEquals(if(removed)0 else 1,f.library.localEntries().size)
        }
    }
    @Test fun explicitQueueSelectionCanRestorePreviousPositionAndNeverDuplicatesEntries() {
        val f=Fixture();val task=f.start();assertEquals(201,f.upload(task).code)
        f.coordinator.add("video000001")
        val id=f.library.localEntries().single().id
        assertEquals(200,f.post("/api/local/add",JSONObject().put("videoIds",JSONArray().put(id)).put("queueVersion",f.coordinator.currentSnapshot().queueState.version)).code)
        val before=f.coordinator.currentSnapshot().queueState
        val body=JSONObject().put("queueItemId",before.items[1].queueItemId).put("queueVersion",before.version).put("play",true)
        val result=f.post("/api/queue/select",body);assertEquals(200,result.code);assertEquals(1,f.prepares)
        assertEquals(before.items,f.coordinator.currentSnapshot().queueState.items);assertEquals(result,f.post("/api/queue/select",body));assertEquals(1,f.prepares)
        val restore=JSONObject().put("queueItemId",before.items[0].queueItemId).put("queueVersion",f.coordinator.currentSnapshot().queueState.version).put("positionMs",12345).put("play",false)
        assertEquals(200,f.post("/api/queue/select",restore).code)
        assertEquals(12345L,f.coordinator.currentSnapshot().queueState.positionMs);assertEquals(PlaybackIntent.STOP,f.coordinator.currentSnapshot().queueState.intent)
    }
    @Test fun realHttpUploadBypassesJsonParserAndRejectsUnauthorizedBodyBeforeReadingIt() {
        val f=Fixture();val server=ParentHttpServer(0,f.controller)
        server.start(3000,true)
        try {
            val task=f.start();val url="http://127.0.0.1:${server.listeningPort}/api/local/upload/${task.getString("id")}"
            val request=okhttp3.Request.Builder().url(url).post(ByteArray(8).toRequestBody("application/octet-stream".toMediaType()))
            f.headers.forEach { (key,value)->request.header(key,value) }
            okhttp3.OkHttpClient().newCall(request.build()).execute().use { response ->assertEquals(201,response.code);assertEquals("READY",JSONObject(response.body!!.string()).getString("status"))}
            java.net.Socket("127.0.0.1",server.listeningPort).use { socket ->
                socket.soTimeout=2000
                val headers="POST /api/local/upload/${UUID.randomUUID()} HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(headers.toByteArray());socket.getOutputStream().flush()
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("401"))
            }
            assertEquals(1,f.library.localEntries().size)
        } finally {server.stop()}
    }
    @Test fun unreadyWatchIsRejectedAndPlaylistPreparationDoesNotChangePlayback() = runBlocking {
        val f=Fixture();val ids=listOf("video000001","video000002")
        val group=QueueGroup.fixed("test","Уроки",ids,QueueGroupSource.YOUTUBE_PLAYLIST)
        f.coordinator.addBatch(ids,0,"group-start",group=group,titles=mapOf(ids[0] to "Название"))
        val before=f.coordinator.currentSnapshot().queueState
        val prepared=f.post("/api/prepared",JSONObject().put("action","ENABLE").put("groupId",group.id).put("queueVersion",before.version))
        assertEquals(200,prepared.code);assertEquals(before,f.coordinator.currentSnapshot().queueState)
        assertEquals(ids,f.library.snapshot().plan);assertEquals(group.id,f.store.read().targetGroupId)
        val denied=f.post("/api/queue/select",JSONObject().put("queueItemId",before.items[0].queueItemId).put("groupId",group.id).put("queueVersion",before.version).put("play",true))
        assertEquals(409,denied.code);assertEquals("VIDEO_NOT_READY",JSONObject(denied.body).getString("error"))
        assertEquals(before,f.coordinator.currentSnapshot().queueState);assertEquals(0,f.prepares)
    }

    @Test fun legacyQueueMigratesAndLocalDiscriminatorCannotBeLostOnRollback() {
        val original=QueueState.Empty.add(QueueItem("one","video000001"))
        val legacy=JSONObject(QueueSnapshotCodec.encode(original)).put("version",1);legacy.getJSONArray("items").getJSONObject(0).remove("local")
        assertFalse(QueueSnapshotCodec.decode(legacy.toString()).currentItem!!.local)
        val local=original.copy(items=listOf(QueueItem("local","local_"+"a".repeat(32),true)))
        val encoded=QueueSnapshotCodec.encode(local);assertEquals(4,JSONObject(encoded).getInt("version"));assertTrue(QueueSnapshotCodec.decode(encoded).currentItem!!.local)
    }
}
