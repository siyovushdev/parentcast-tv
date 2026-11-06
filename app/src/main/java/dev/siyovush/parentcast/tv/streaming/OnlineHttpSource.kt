package dev.siyovush.parentcast.tv.streaming

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.media.VkMediaPolicy
import dev.siyovush.parentcast.tv.prepared.parseContentRange
import dev.siyovush.parentcast.tv.youtube.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One continuous HTTP read into Media3's RAM buffer. No disk, block assembly or quota scan. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlineHttpSource(
    private val session:YouTubeSession,
    private val resolve:(Boolean)->StreamSpec,
    private val client:OkHttpClient=client(),
    private val allowed:(StreamSpec,HttpUrl)->Boolean=::allowed,
    private val register:(Call)->Unit={},
    private val unregister:(Call)->Unit={},
) : BaseDataSource(true) {
    @Volatile private var call:Call?=null
    @Volatile private var closed=false
    private var response:Response?=null
    private var remaining=C.LENGTH_UNSET.toLong()
    private var started=false
    private var uri:Uri?=null
    override fun getUri():Uri?=uri // Only the opaque parentcast-online URI, never a signed CDN URL.
    override fun open(data:DataSpec):Long {
        closed=false;uri=data.uri;transferInitializing(data)
        require(data.position>=0 && (data.length==C.LENGTH_UNSET.toLong() || data.length>0))
        try {
            for(attempt in 0..1) {
                val spec=resolve(attempt>0)
                var url=spec.url.toHttpUrl()
                var expired=false
                for(redirect in 0..7) {
                    if(closed)throw RangeFailure("TRANSFER_CANCELLED")
                    if(!allowed(spec,url))throw RangeFailure("UNSUPPORTED_MEDIA_HOST")
                    val end=if(data.length==C.LENGTH_UNSET.toLong())null else Math.addExact(data.position,data.length)-1
                    val request=Request.Builder().url(url).header("User-Agent",spec.headers.entries.firstOrNull {it.key.equals("User-Agent",true)}?.value ?: YOUTUBE_USER_AGENT)
                        .header("Accept-Encoding","identity").header("Range","bytes=${data.position}-${end ?: ""}")
                    spec.headers.filterKeys {it.lowercase() in setOf("accept","accept-language","origin","referer")}.forEach {(k,v)->request.header(k,v)}
                    if(spec.provider==MediaProvider.YOUTUBE)CookieHeaderCodec.merge(emptyList(),session.cookiesFor(url))?.let {request.header("Cookie",it)}
                    val next=client.newCall(request.build());call=next;register(next)
                    if(closed){next.cancel();throw RangeFailure("TRANSFER_CANCELLED")}
                    val result=next.execute()
                    if(result.code in setOf(301,302,303,307,308)) {
                        val location=result.header("Location")?.let(url::resolve);result.close();unregister(next)
                        url=location ?: throw RangeFailure("UNSUPPORTED_MEDIA_HOST");continue
                    }
                    if(result.code in setOf(403,410) && attempt==0){result.close();unregister(next);expired=true;break}
                    response=result
                    if(result.code !in setOf(200,206))throw RangeFailure(if(result.code in setOf(403,410))"URL_EXPIRED" else "NETWORK_RETRY")
                    val body=result.body ?: throw RangeFailure("NETWORK_RETRY")
                    val bytes=if(result.code==206) {
                        val range=parseContentRange(result.header("Content-Range")) ?: throw RangeFailure("INVALID_RANGE")
                        if(range.start!=data.position || (end!=null && range.end> end) || (spec.sizeBytes!=null && range.total!=spec.sizeBytes) ||
                            (body.contentLength()>=0 && body.contentLength()!=range.end-range.start+1))throw RangeFailure("INVALID_RANGE")
                        range.end-range.start+1
                    } else {
                        if(data.position!=0L)throw RangeFailure("RANGE_UNSUPPORTED")
                        if(spec.sizeBytes!=null && body.contentLength()>=0 && body.contentLength()!=spec.sizeBytes)throw RangeFailure("REPRESENTATION_CHANGED")
                        body.contentLength()
                    }
                    remaining=if(data.length==C.LENGTH_UNSET.toLong())bytes else if(bytes<0)data.length else minOf(bytes,data.length)
                    started=true;transferStarted(data);return remaining
                }
                if(!expired)throw RangeFailure("REDIRECT_LIMIT")
            }
            throw RangeFailure("NETWORK_RETRY")
        }catch(error:Exception){
            val cancelled=closed || call?.isCanceled()==true
            close()
            val reason=when {
                cancelled -> "TRANSFER_CANCELLED"
                error is RangeFailure -> error.reason
                error is YouTubeSourceException -> when {
                    error.failure.stopRequested -> "YOUTUBE_AUTH_REQUIRED"
                    error.failure.kind==dev.siyovush.parentcast.tv.playback.PlaybackErrorKind.UNAVAILABLE -> "UNAVAILABLE"
                    error.failure.kind==dev.siyovush.parentcast.tv.playback.PlaybackErrorKind.UNSUPPORTED -> "UNSUPPORTED"
                    else -> "NETWORK_RETRY"
                }
                error.message in setOf("QUALITY_UNAVAILABLE","REPRESENTATION_CHANGED","ONLINE_SELECTION_ERROR") -> error.message!!
                else -> "NETWORK_RETRY"
            }
            throw RangeFailure(reason)
        }
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        if(closed)throw RangeFailure("TRANSFER_CANCELLED")
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val count=try {response!!.body!!.byteStream().read(buffer,offset,if(remaining<0)length else minOf(length.toLong(),remaining).toInt())}
            catch(_:IOException){throw RangeFailure(if(closed || call?.isCanceled()==true)"TRANSFER_CANCELLED" else "NETWORK_RETRY")}
        if(count<0){if(remaining>0)throw RangeFailure("NETWORK_RETRY");return C.RESULT_END_OF_INPUT}
        if(remaining>=0)remaining-=count
        bytesTransferred(count);return count
    }
    override fun close() {
        closed=true
        call?.let {it.cancel();unregister(it)};call=null
        response?.close();response=null
        if(started){started=false;transferEnded()}
    }
    companion object {
        fun client():OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).dns(VkMediaPolicy.dns)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).build()
        fun allowed(spec:StreamSpec,url:HttpUrl)=if(spec.provider==MediaProvider.VK)VkMediaPolicy.allows(url) else
            url.scheme=="https" && url.port==443 && url.username.isEmpty() && url.password.isEmpty() && url.host.endsWith(".googlevideo.com")
    }
}
