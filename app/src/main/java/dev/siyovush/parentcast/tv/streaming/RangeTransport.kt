package dev.siyovush.parentcast.tv.streaming

import dev.siyovush.parentcast.tv.media.MediaProvider
import dev.siyovush.parentcast.tv.media.VkMediaPolicy
import dev.siyovush.parentcast.tv.prepared.parseContentRange
import dev.siyovush.parentcast.tv.prepared.strongResumeValidator
import dev.siyovush.parentcast.tv.youtube.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class RangeIdentity(val total:Long,val validator:String,val etag:Boolean,val representation:String)
internal class RangeFailure(val reason:String):IOException(reason)
internal class OpenRange(val input:InputStream,val bytes:Long,val identity:RangeIdentity,private val response:Response):Closeable {
    override fun close()=response.close()
}

/** Signed URLs remain in RAM. Both providers use explicit public-CDN and redirect checks. */
internal class RangeTransport(
    private val session:YouTubeSession,
    private val client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .dns(VkMediaPolicy.dns).connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS).build(),
    private val allowed:(StreamSpec,HttpUrl)->Boolean={ spec,url ->
        if(spec.provider==MediaProvider.VK)VkMediaPolicy.allows(url)
        else url.scheme=="https" && url.port==443 && url.username.isEmpty() && url.password.isEmpty() && url.host.endsWith(".googlevideo.com")
    },
) {
    fun probe(spec:StreamSpec):RangeIdentity = open(spec,0,1,null).use { it.identity }
    fun open(spec:StreamSpec,position:Long,length:Long,expected:RangeIdentity?,onCall:(Call?)->Unit={}):OpenRange {
        require(position>=0 && length>0 && length<=512*1024)
        var url=spec.url.toHttpUrl()
        repeat(8) {
            if(!allowed(spec,url))throw RangeFailure("UNSUPPORTED_MEDIA_HOST")
            val end=Math.addExact(position,length)-1
            val request=Request.Builder().url(url).header("User-Agent",spec.headers.entries.firstOrNull { it.key.equals("User-Agent",true) }?.value ?: YOUTUBE_USER_AGENT)
                .header("Accept-Encoding","identity").header("Range","bytes=$position-$end")
            spec.headers.filterKeys { it.lowercase() in setOf("accept","accept-language","origin","referer") }.forEach { (k,v)->request.header(k,v) }
            if(spec.provider==MediaProvider.YOUTUBE)CookieHeaderCodec.merge(emptyList(),session.cookiesFor(url))?.let { request.header("Cookie",it) }
            expected?.validator?.takeIf { it.isNotEmpty() }?.let { request.header("If-Range",it) }
            val call=client.newCall(request.build());onCall(call)
            val response=try { call.execute() }catch(e:IOException){onCall(null);throw RangeFailure(if(call.isCanceled())"TRANSFER_CANCELLED" else "NETWORK_RETRY")}
            if(response.code in setOf(301,302,303,307,308)) {
                val next=response.header("Location")?.let(url::resolve);response.close();onCall(null)
                url=next ?: throw RangeFailure("UNSUPPORTED_MEDIA_HOST");return@repeat
            }
            try {
                if(response.code in setOf(403,410))throw RangeFailure("URL_EXPIRED")
                if(response.code in setOf(408,429,500,502,503,504))throw RangeFailure("NETWORK_RETRY")
                if(response.code!=206)throw RangeFailure(if(response.code==200 && expected!=null)"REPRESENTATION_CHANGED" else "RANGE_UNSUPPORTED")
                val range=parseContentRange(response.header("Content-Range")) ?: throw RangeFailure("INVALID_RANGE")
                val body=response.body ?: throw RangeFailure("NETWORK_RETRY")
                if(range.start!=position || range.end!=minOf(end,range.total-1) || body.contentLength()!=range.end-range.start+1)throw RangeFailure("INVALID_RANGE")
                if(spec.sizeBytes!=null && range.total!=spec.sizeBytes)throw RangeFailure("REPRESENTATION_CHANGED")
                val validator=strongResumeValidator(response.header("ETag"),response.header("Last-Modified"),response.header("Date"))
                if(validator==null && !spec.resumeIdentity.contains("|revision="))throw RangeFailure("RANGE_IDENTITY_UNCONFIRMED")
                val identity=RangeIdentity(range.total,validator?.first.orEmpty(),validator?.second ?: false,spec.resumeIdentity)
                if(expected!=null && identity!=expected)throw RangeFailure("REPRESENTATION_CHANGED")
                return OpenRange(body.byteStream(),body.contentLength(),identity,response)
            }catch(error:Exception){response.close();onCall(null);throw error}
        }
        throw RangeFailure("REDIRECT_LIMIT")
    }
}

/** Foreground demand/seek can interrupt bounded low-priority HTTP, but never another playback track. */
internal class StreamPriority {
    private val background=mutableMapOf<Call,String>()
    private val foreground=mutableMapOf<String,MutableSet<Call>>()
    private var readers=0
    @Volatile var currentId:String?=null
    @Synchronized fun enterForeground(){readers++;background.keys.toList().forEach(Call::cancel)}
    @Synchronized fun leaveForeground(){readers=(readers-1).coerceAtLeast(0)}
    @Synchronized fun backgroundAllowed()=readers==0
    @Synchronized fun register(id:String,call:Call,playback:Boolean) {
        if(playback)foreground.getOrPut(id){mutableSetOf()}.add(call)
        else { if(readers>0){call.cancel();throw RangeFailure("PRIORITY_YIELD")};background[call]=id }
    }
    @Synchronized fun unregister(call:Call){background.remove(call);foreground.values.forEach { it.remove(call) }}
    @Synchronized fun cancelPlayback(id:String?=null){foreground.filterKeys { id==null || it==id }.values.flatMap { it.toList() }.forEach(Call::cancel)}
    @Synchronized fun cancelBackground(id:String){background.filterValues { it==id }.keys.toList().forEach(Call::cancel)}
    @Synchronized fun cancelAll(){background.keys.toList().forEach(Call::cancel);cancelPlayback()}
}
