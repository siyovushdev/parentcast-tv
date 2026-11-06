package dev.siyovush.parentcast.tv.streaming

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import dev.siyovush.parentcast.tv.media.*
import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class StreamStart(val videoId:String,val quality:String,val height:Int,val alreadyReady:Boolean)
internal data class StreamStatus(val videoId:String,val mode:String="STREAM_SAVE",val quality:String,val height:Int,val downloadedBytes:Long,val totalBytes:Long?,val percent:Int?,val error:String)
internal data class CachedTrack(val key:String,val audio:Boolean,val identity:RangeIdentity,val mime:String)
internal data class CachedVideo(val id:String,val title:String,val duration:Long,val quality:String,val height:Int,val storageId:String,val tracks:List<CachedTrack>)

@androidx.annotation.OptIn(UnstableApi::class)
internal class StreamingLibrary(
    context:Context,private val library:PreparedLibrary,private val catalog:QualitySource,private val transport:RangeTransport,
) {
    private val store=library.streamingStore()
    private val journal=File(store.directory,"streaming-plans.json")
    private val database=StandaloneDatabaseProvider(context.applicationContext)
    private val plans=ConcurrentHashMap<String,CachedVideo>()
    private val resolved=ConcurrentHashMap<String,ResolvedVideo>()
    private val previews=ConcurrentHashMap<String,Pair<Long,VideoPreview>>()
    private val caches=mutableMapOf<String,SimpleCache>()
    private val guards=ConcurrentHashMap<String,Any>()
    private val blocked=ConcurrentHashMap.newKeySet<String>()
    private val activeBlocks=ConcurrentHashMap<String,AtomicInteger>()
    private val completed=ConcurrentHashMap.newKeySet<String>()
    private val materialized=ConcurrentHashMap.newKeySet<String>()
    private val admissions=ConcurrentHashMap<String,Mutex>()
    private val failures=ConcurrentHashMap<String,String>()
    private val priority=StreamPriority()
    @Volatile private var corrupt=false
    init {
        if(journal.isFile)try {
            val a=JSONObject(journal.readText()).getJSONArray("videos")
            require(a.length()<=10_000)
            repeat(a.length()) { n -> val j=a.getJSONObject(n);val id=j.getString("id");require(OnlineMediaIds.isOnline(id))
                val t=j.getJSONArray("tracks")
                val tracks=List(t.length()) { i -> val v=t.getJSONObject(i);val key=v.getString("key");require(key.matches(Regex("[a-f0-9]{64}")))
                    CachedTrack(key,v.getBoolean("audio"),RangeIdentity(v.getLong("total"),v.getString("validator"),v.getBoolean("etag"),v.getString("representation")),v.getString("mime")) }
                require(tracks.size in 1..2 && tracks.all { it.identity.total>0 })
                plans[id]=CachedVideo(id,j.getString("title"),j.getLong("duration"),j.getString("quality"),j.getInt("height"),j.getString("storageId"),tracks)
            }
        }catch(_:Exception){corrupt=true}
        library.installStreaming(this)
    }
    suspend fun preview(videoId:String):VideoPreview {
        require(OnlineMediaIds.isOnline(videoId))
        return catalog.preview(videoId).also { p -> if(previews.size>=64)previews.clear();previews[videoId]=System.currentTimeMillis() to p }
    }
    suspend fun start(videoId:String,qualityId:String,storageId:String,storageVersion:Long,watchNow:Boolean=false):StreamStart = admissions.getOrPut(videoId){Mutex()}.withLock { withContext(Dispatchers.IO) {
        require(OnlineMediaIds.isOnline(videoId) && qualityId.matches(Regex("[a-f0-9]{64}")))
        check(!corrupt) { "STREAM_CATALOGUE_ERROR" }
        val old=plans[videoId]
        if(library.mediaReadiness(videoId)=="READY")return@withContext StreamStart(videoId,old?.quality ?: qualityId,old?.height ?: 0,true)
        library.reserveStreaming(videoId,storageId,storageVersion)
        try {
        if(old!=null) {
            check(old.quality==qualityId && old.storageId==storageId) { "STREAM_SELECTION_CONFLICT" }
            library.storage.checkVersion(storageVersion);check(library.storage.selectedId()==storageId) { "STORAGE_CHANGED" }
            blocked.remove(videoId);failures.remove(videoId);if(watchNow)syncPlayback(videoId)
            ensureResolved(old);adoptLegacy(old)
            library.enableStreaming(videoId);return@withContext StreamStart(videoId,qualityId,old.height,false)
        }
        val metadata=previews[videoId]?.takeIf { System.currentTimeMillis()-it.first<300_000 }?.second ?: preview(videoId)
        val quality=metadata.qualities.find { it.id==qualityId } ?: throw IllegalStateException("QUALITY_UNAVAILABLE")
        val tracks=listOfNotNull(quality.resolved.video,quality.resolved.audio).mapIndexed { index,spec ->
            val identity=transport.probe(spec)
            val key=hash("$videoId|$qualityId|$index|${identity.representation}|${identity.total}|${identity.validator}|${identity.etag}")
            CachedTrack(key,index==1,identity,spec.mimeType)
        }
        // Cache + verified materialization coexist until READY. Reserve that honest temporary headroom.
        val total=tracks.sumOf { it.identity.total }
        val quota=library.storage.limit(storageId)
        check(total<=quota/2 && library.storage.usedBytes(storageId)<=quota-total*2 && library.storage.volume(storageId).freeBytes-FREE_RESERVE>=total*2) { "NO_SPACE" }
        val plan=CachedVideo(videoId,metadata.title,metadata.durationSeconds,qualityId,quality.height,storageId,tracks)
        synchronized(this@StreamingLibrary) { check(plans[videoId]==null) { "STREAM_SELECTION_CONFLICT" };plans[videoId]=plan;try { persist() }catch(e:Exception){plans.remove(videoId);throw e} }
        resolved[videoId]=quality.resolved;blocked.remove(videoId);if(watchNow)syncPlayback(videoId)
        adoptLegacy(plan)
        library.enableStreaming(videoId)
        StreamStart(videoId,qualityId,quality.height,false)
        }finally{library.releaseStreaming(videoId)}
    } }
    fun isEnabled(id:String)=!corrupt && plans[id]?.storageId==library.mediaStorageId(id) && plans.containsKey(id) && id !in blocked
    fun canPlayDuringDownload(id:String)=isEnabled(id) && runCatching { library.storage.root(plans.getValue(id).storageId,true);true }.getOrDefault(false) &&
        failures[id] !in setOf("REPRESENTATION_CHANGED","NO_SPACE","RANGE_UNSUPPORTED","RANGE_IDENTITY_UNCONFIRMED","STORAGE_ERROR","MEDIA_VALIDATION_FAILED","UNAVAILABLE","UNSUPPORTED","YOUTUBE_AUTH_REQUIRED") &&
        library.mediaReadiness(id) !in setOf("DELETED","DELETING","STORAGE_BUSY")
    fun pendingIds()=plans.keys.filter { it !in blocked && library.mediaReadiness(it)!="READY" }
    fun syncPlayback(id:String?) { val old=priority.currentId;priority.currentId=id;if(old!=id)priority.cancelPlayback(old) }
    fun source(fallback:YouTubeSource)=object:YouTubeSource {
        override suspend fun resolve(videoId:String):ResolvedVideo {
            if(library.mediaReadiness(videoId)=="READY")return fallback.resolve(videoId)
            val plan=plans[videoId]?.takeIf { isEnabled(videoId) } ?: return fallback.resolve(videoId)
            check(canPlayDuringDownload(videoId)) { failures[videoId] ?: "STREAM_UNAVAILABLE" }
            try { ensureResolved(plan) }catch(error:RangeFailure){throw YouTubeSourceException(PlaybackFailure(
                if(error.reason in setOf("REPRESENTATION_CHANGED","RANGE_UNSUPPORTED","RANGE_IDENTITY_UNCONFIRMED","QUALITY_UNAVAILABLE"))PlaybackErrorKind.UNSUPPORTED else PlaybackErrorKind.NETWORK,
                diagnostic=error.reason),error)}
            return ResolvedVideo(plan.id,plan.title,plan.duration,cacheSpec(plan,plan.tracks[0]),plan.tracks.getOrNull(1)?.let { cacheSpec(plan,it) },"${plan.height}p · просмотр с сохранением · H.264/AAC")
        }
    }
    private fun cacheSpec(plan:CachedVideo,t:CachedTrack)=StreamSpec("parentcast-cache://${t.key}",t.mime,emptyMap(),t.identity.total,provider=if(OnlineMediaIds.isVk(plan.id))MediaProvider.VK else MediaProvider.YOUTUBE,sharedCacheKey=t.key)
    fun dataSourceFactory(spec:StreamSpec):DataSource.Factory? {
        val key=spec.sharedCacheKey ?: return null
        val plan=plans.values.firstOrNull { p->p.tracks.any { it.key==key } } ?: throw IllegalArgumentException("Unknown cached track")
        val track=plan.tracks.single { it.key==key }
        return DataSource.Factory { BoundedSource(plan,track,true) }
    }
    fun states():List<StreamStatus> = plans.keys.mapNotNull(::state)
    fun state(id:String):StreamStatus? {
        val plan=plans[id] ?: return null
        val total=plan.tracks.sumOf { it.identity.total }
        val bytes=if(library.mediaReadiness(id)=="READY")total else synchronized(caches) { caches[plan.storageId] }?.let { c -> plan.tracks.sumOf { c.getCachedBytes(it.key,0,it.identity.total) } } ?: 0
        return StreamStatus(id,quality=plan.quality,height=plan.height,downloadedBytes=bytes,totalBytes=total,percent=(bytes.toDouble()*100/total).toInt().coerceIn(0,if(library.mediaReadiness(id)=="READY")100 else 99),error=failures[id].orEmpty())
    }
    suspend fun complete(id:String,progress:(Long,Long,String)->Unit):Pair<ResolvedVideo,List<LocalTrack>> = coroutineScope {
        val plan=plans[id] ?: error("STREAM_NOT_CONFIGURED")
        ensureResolved(plan)
        val total=plan.tracks.sumOf { it.identity.total }
        val bytes=ConcurrentHashMap<String,Long>()
        val cancelled=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() }finally{priority.cancelBackground(id)} }
        try {
        val results=plan.tracks.map { track -> async(Dispatchers.IO) {
            materializedTrack(plan,track)?.let { materialized.add(track.key);bytes[track.key]=it.bytes;progress(bytes.values.sum(),total,"VERIFY");return@async it }
            val count=(track.identity.total+BLOCK-1)/BLOCK
            for(block in 0 until count) {
                ensureActive();checkAllowed(plan)
                while(!priority.backgroundAllowed()) { delay(30);ensureActive() }
                try { readBlock(plan,track,block*BLOCK,false) }catch(e:RangeFailure) { if(e.reason in setOf("PRIORITY_YIELD","TRANSFER_CANCELLED")){delay(50);continue}else throw e }
                bytes[track.key]=cache(plan).getCachedBytes(track.key,0,track.identity.total)
                progress(bytes.values.sum(),total,"CACHE")
            }
            // Fill any interrupted block before materialization. Reads use the same cache keys as Media3.
            while(cache(plan).getCachedBytes(track.key,0,track.identity.total)<track.identity.total) {
                ensureActive();for(block in 0 until count)if(!cache(plan).isCached(track.key,block*BLOCK,minOf(BLOCK,track.identity.total-block*BLOCK))) {
                    while(!priority.backgroundAllowed()){delay(30);ensureActive()}
                    try { readBlock(plan,track,block*BLOCK,false) }catch(e:RangeFailure){if(e.reason !in setOf("PRIORITY_YIELD","TRANSFER_CANCELLED"))throw e;delay(50)}
                }
            }
            val target=store.track(id,track.audio);val partial=PreparedStorage.safeFile(target.parentFile!!,target.name+".stream.part")
            try {
                FileOutputStream(partial).use { output ->
                    for(block in 0 until count) {
                        ensureActive();val data=readBlock(plan,track,block*BLOCK,false,cacheOnly=true)
                        library.budget.write(output,data,data.size,target)
                    };output.fd.sync()
                }
                check(partial.length()==track.identity.total) { "DOWNLOAD_FAILED" }
                val context=currentCoroutineContext();val checksum=sha256(partial){context.ensureActive()}
                ResumeFiles(target).write(ResumeRecord(track.identity.representation,track.identity.total,track.identity.validator,track.identity.etag,true,checksum))
                check(partial.renameTo(target)) { "STORAGE_ERROR" }
                materialized.add(track.key)
                LocalTrack(store.trackName(id,track.audio),track.identity.total,checksum)
            }finally{partial.delete()}
        } }.awaitAll()
        resolved.getValue(id) to results
        }finally{cancelled.cancelAndJoin()}
    }
    private fun materializedTrack(plan:CachedVideo,track:CachedTrack):LocalTrack? {
        val target=store.track(plan.id,track.audio);val record=ResumeFiles(target).read() ?: return null
        if(!record.complete || record.identity!=track.identity.representation || record.total!=track.identity.total || record.validator!=track.identity.validator || record.etag!=track.identity.etag)return null
        val partial=PreparedStorage.safeFile(target.parentFile!!,target.name+".stream.part")
        if(!target.isFile && partial.isFile && partial.length()==record.total && sha256(partial)==record.sha)check(partial.renameTo(target)) { "STORAGE_ERROR" }
        return if(target.isFile && target.length()==record.total && sha256(target)==record.sha)LocalTrack(store.trackName(plan.id,track.audio),record.total,record.sha) else null
    }
    fun onReady(id:String) {
        completed.add(id);failures.remove(id)
        val plan=plans[id] ?: return
        synchronized(guards.getOrPut(id){Any()}) { removeCompletedCache(plan) }
    }
    private fun removeCompletedCache(plan:CachedVideo) {
        // Cache cleanup must never turn a successfully verified READY file into an error.
        if(plan.id in completed && (activeBlocks[plan.id]?.get() ?: 0)==0)
            runCatching { synchronized(caches){caches[plan.storageId]}?.let { c->plan.tracks.forEach { c.removeResource(it.key) } } }
    }
    fun invalidateMaterialized(id:String){plans[id]?.tracks?.forEach { materialized.remove(it.key) };completed.remove(id);failures[id]="MEDIA_VALIDATION_FAILED"}
    fun moved(id:String,storageId:String) {
        val previous=plans[id] ?: return
        synchronized(this) { plans[id]=previous.copy(storageId=storageId);persist() }
        completed.add(id);synchronized(guards.getOrPut(id){Any()}){removeCompletedCache(previous)}
    }
    /** Runtime shutdown/tests: the process-wide owner must stop playback/preparation first. */
    fun close(){priority.cancelAll();synchronized(caches){caches.values.forEach(SimpleCache::release);caches.clear()};database.close()}
    fun block(ids:Collection<String>){blocked.addAll(ids);ids.forEach { priority.cancelPlayback(it);priority.cancelBackground(it) }}
    suspend fun delete(ids:Collection<String>) {
        block(ids)
        for(id in ids){withTimeout(10_000){while((activeBlocks[id]?.get() ?: 0)>0)delay(20)};plans[id]?.let { p->p.tracks.forEach { cache(p).removeResource(it.key) } }}
        synchronized(this){ids.forEach { plans.remove(it)?.tracks?.forEach { track->materialized.remove(track.key) };resolved.remove(it);completed.remove(it);failures.remove(it) };persist()}
    }
    private fun checkAllowed(plan:CachedVideo) {
        if(plan.id in blocked)throw RangeFailure("TRANSFER_CANCELLED")
        library.storage.root(plan.storageId,true)
        if(library.mediaReadiness(plan.id)=="STORAGE_BUSY")throw RangeFailure("STORAGE_BUSY")
    }
    /** Reuse a prior resumable prefix only after the exact revision/validator/length was reconfirmed. */
    private fun adoptLegacy(plan:CachedVideo) {
        for(track in plan.tracks) {
            val target=store.track(plan.id,track.audio);val files=ResumeFiles(target)
            val existing=if(target.isFile)target else files.partial
            if(!existing.isFile || existing.length()==0L)continue
            val record=files.read() ?: throw RangeFailure("STREAM_PARTIAL_CONFLICT")
            if(record.identity!=track.identity.representation || record.total!=track.identity.total || record.validator!=track.identity.validator || record.etag!=track.identity.etag || existing.length()>record.total)
                throw RangeFailure("STREAM_PARTIAL_CONFLICT")
            if(record.complete && (record.sha.isEmpty() || sha256(existing)!=record.sha))throw RangeFailure("STREAM_PARTIAL_CONFLICT")
            if(record.complete && existing==target){materialized.add(track.key);continue}
            val cache=cache(plan);val length=existing.length()
            val source=CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .setCacheWriteDataSinkFactory(sinkFactory(plan,cache))
                .setUpstreamDataSourceFactory(DataSource.Factory { object:DataSource {
                    private val file=FileDataSource()
                    override fun addTransferListener(listener:TransferListener)=file.addTransferListener(listener)
                    override fun open(spec:DataSpec)=file.open(spec.buildUpon().setUri(Uri.fromFile(existing)).build())
                    override fun getUri():Uri=Uri.parse("parentcast-cache://${track.key}")
                    override fun read(buffer:ByteArray,offset:Int,length:Int)=file.read(buffer,offset,length)
                    override fun close()=file.close()
                } }).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri("parentcast-cache://${track.key}").setKey(track.key).setLength(length).build())
                val buffer=ByteArray(64*1024);var bytes=0L
                while(bytes<length){val count=source.read(buffer,0,minOf(buffer.size.toLong(),length-bytes).toInt());if(count<0)throw RangeFailure("INCOMPLETE_RANGE");bytes+=count}
            }finally{source.close()}
            check(cache.getCachedBytes(track.key,0,length)==length) { "STREAM_PARTIAL_CONFLICT" }
            check(existing.delete()) { "STORAGE_ERROR" }
            File(target.path+".resume.json").delete();File(target.path+".resume.json.tmp").delete()
        }
    }
    private fun sinkFactory(plan:CachedVideo,cache:SimpleCache)=DataSink.Factory {
        val delegate=CacheDataSink.Factory().setCache(cache).setBufferSize(0).setFragmentSize(BLOCK).createDataSink()
        object:DataSink {
            override fun open(spec:DataSpec)=delegate.open(spec)
            override fun write(buffer:ByteArray,offset:Int,length:Int) { library.budget.writeCache(plan.storageId,length) { delegate.write(buffer,offset,length) } }
            override fun close()=delegate.close()
        }
    }
    private fun cache(plan:CachedVideo):SimpleCache {
        val root=library.storage.root(plan.storageId,true)
        return synchronized(caches) { caches.getOrPut(plan.storageId) {
            val directory=PreparedStorage.safeFile(root,"stream-cache");check(directory.isDirectory || directory.mkdir()) { "STORAGE_ERROR" }
            SimpleCache(directory,NoOpCacheEvictor(),database).also { it.checkInitialization() }
        } }
    }
    private suspend fun ensureResolved(plan:CachedVideo,refresh:Boolean=false):ResolvedVideo {
        if(!refresh)resolved[plan.id]?.let { return it }
        return withContext(Dispatchers.IO) { synchronized(guards.getOrPut("resolve:${plan.id}"){Any()}) {
            if(!refresh)resolved[plan.id]?.let { return@synchronized it }
            val metadata=runBlocking { catalog.preview(plan.id) }
            val selected=metadata.qualities.find { it.id==plan.quality }?.resolved ?: throw RangeFailure("QUALITY_UNAVAILABLE")
            val specs=listOfNotNull(selected.video,selected.audio)
            if(specs.size!=plan.tracks.size)throw RangeFailure("REPRESENTATION_CHANGED")
            specs.forEachIndexed { i,spec -> if(transport.probe(spec)!=plan.tracks[i].identity)throw RangeFailure("REPRESENTATION_CHANGED") }
            resolved[plan.id]=selected;selected
        } }
    }
    private fun readBlock(plan:CachedVideo,track:CachedTrack,position:Long,playback:Boolean,cacheOnly:Boolean=false):ByteArray {
        require(position>=0 && position<track.identity.total)
        val length=minOf(BLOCK,track.identity.total-position).toInt()
        val guard=guards.getOrPut(plan.id){Any()}
        synchronized(guard) {
            if(track.key in materialized || plan.id in completed || library.mediaReadiness(plan.id)=="READY") {
                return try { ByteArray(length).also { data -> RandomAccessFile(store.track(plan.id,track.audio),"r").use { f->f.seek(position);f.readFully(data) } } }
                catch(_:Exception){failures[plan.id]="STORAGE_ERROR";throw RangeFailure("STORAGE_ERROR")}
            }
            activeBlocks.getOrPut(plan.id){AtomicInteger()}.incrementAndGet()
        }
        if(playback)priority.enterForeground()
        try {
            checkAllowed(plan)
            val cache=cache(plan)
            val factory=CacheDataSource.Factory().setCache(cache).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .setUpstreamDataSourceFactory(if(cacheOnly)null else DataSource.Factory { Upstream(plan,track,playback) })
                .setCacheWriteDataSinkFactory(sinkFactory(plan,cache))
            val source=factory.createDataSource()
            return try {
                source.open(DataSpec.Builder().setUri("parentcast-cache://${track.key}").setKey(track.key).setPosition(position).setLength(length.toLong()).build())
                val data=ByteArray(length);var offset=0
                while(offset<length){val n=source.read(data,offset,length-offset);if(n<0)throw RangeFailure("INCOMPLETE_RANGE");offset+=n}
                data
            }finally{source.close()}
        }catch(error:Exception){
            val code=when(error) {
                is RangeFailure -> error.reason
                is StorageUnavailable -> error.code
                is YouTubeSourceException -> when {
                    error.failure.stopRequested -> "YOUTUBE_AUTH_REQUIRED"
                    error.failure.kind==PlaybackErrorKind.UNAVAILABLE -> "UNAVAILABLE"
                    error.failure.kind==PlaybackErrorKind.UNSUPPORTED -> "UNSUPPORTED"
                    else -> "NETWORK_RETRY"
                }
                is MediaStorageFailure,is Cache.CacheException,is FileDataSource.FileDataSourceException -> "STORAGE_ERROR"
                else -> error.message?.takeIf { it in setOf("NO_SPACE","STORAGE_ERROR") } ?: "NETWORK_RETRY"
            }
            if(code !in setOf("PRIORITY_YIELD","TRANSFER_CANCELLED"))failures[plan.id]=code
            throw if(error is RangeFailure)error else RangeFailure(code)
        }finally{
            if(playback)priority.leaveForeground()
            synchronized(guard){activeBlocks.getValue(plan.id).decrementAndGet();removeCompletedCache(plan)}
        }
    }
    private inner class BoundedSource(private val plan:CachedVideo,private val track:CachedTrack,private val playback:Boolean):DataSource {
        private var position=0L;private var end=0L;private var blockAt=-1L;private var data=ByteArray(0)
        override fun addTransferListener(listener:TransferListener)=Unit
        override fun open(spec:DataSpec):Long { position=spec.position;require(position<=track.identity.total);end=if(spec.length==C.LENGTH_UNSET.toLong())track.identity.total else minOf(track.identity.total,position+spec.length);return end-position }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(length==0)return 0;if(position>=end)return C.RESULT_END_OF_INPUT
            val start=position/BLOCK*BLOCK
            if(start!=blockAt){data=readBlock(plan,track,start,playback);blockAt=start}
            val within=(position-blockAt).toInt();val count=minOf(length.toLong(),(data.size-within).toLong(),end-position).toInt()
            data.copyInto(buffer,offset,within,within+count);position+=count;return count
        }
        override fun getUri():Uri=Uri.parse("parentcast-cache://${track.key}")
        override fun close(){data=ByteArray(0);blockAt=-1}
    }
    private inner class Upstream(private val plan:CachedVideo,private val track:CachedTrack,private val playback:Boolean):DataSource {
        private var response:OpenRange?=null;private var call:okhttp3.Call?=null
        override fun addTransferListener(listener:TransferListener)=Unit
        override fun open(spec:DataSpec):Long {
            repeat(3){attempt ->
                val video=runBlocking { ensureResolved(plan,attempt>0) };val selected=if(track.audio)video.audio!! else video.video
                try {
                    response=transport.open(selected,spec.position,spec.length,track.identity) { new -> call?.let(priority::unregister);call=new;if(new!=null)priority.register(plan.id,new,playback) }
                    failures.remove(plan.id);return response!!.bytes
                }catch(e:RangeFailure){if(e.reason!="URL_EXPIRED" || attempt==2)throw e}
            };throw RangeFailure("NETWORK_RETRY")
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int=try { response!!.input.read(buffer,offset,length) }catch(_:IOException){throw RangeFailure(if(call?.isCanceled()==true)"TRANSFER_CANCELLED" else "NETWORK_RETRY")}
        override fun getUri():Uri=Uri.parse("parentcast-cache://${track.key}")
        override fun close(){response?.close();response=null;call?.let(priority::unregister);call=null}
    }
    @Synchronized private fun persist() {
        check(!corrupt) { "STREAM_CATALOGUE_ERROR" }
        val root=JSONObject().put("version",1).put("videos",JSONArray().apply { plans.values.forEach { p -> put(JSONObject().put("id",p.id).put("title",p.title).put("duration",p.duration).put("quality",p.quality).put("height",p.height).put("storageId",p.storageId)
            .put("tracks",JSONArray().apply { p.tracks.forEach { t -> put(JSONObject().put("key",t.key).put("audio",t.audio).put("mime",t.mime).put("total",t.identity.total).put("validator",t.identity.validator).put("etag",t.identity.etag).put("representation",t.identity.representation)) } })) } })
        val tmp=File(journal.path+".tmp");FileOutputStream(tmp).use { it.write(root.toString().toByteArray());it.fd.sync() };check(tmp.renameTo(journal)) { "STREAM_CATALOGUE_ERROR" }
    }
    companion object { const val BLOCK=512L*1024;private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) } }
}

internal object StreamingRuntime {
    @Volatile private var instance:StreamingLibrary?=null
    fun get(context:Context,prepared:PreparedLibrary,session:YouTubeSession):StreamingLibrary=instance ?: synchronized(this) {
        instance ?: run {
            val yt=dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpYouTubeSource(context,session,publicOnly=true)
            val vk=VkVideoSource(context)
            StreamingLibrary(context,prepared,QualitySource { id->if(OnlineMediaIds.isVk(id))vk.preview(id) else yt.preview(id) },RangeTransport(session)).also { instance=it }
        }
    }
}
