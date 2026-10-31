package dev.siyovush.parentcast.tv.youtube

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

class OkHttpDownloader(
    private val session: YouTubeSession = DisabledYouTubeSession,
) : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", YOUTUBE_USER_AGENT)
        }
        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val contentType = request.headers().entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?.toMediaType()
                builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody(contentType))
            }
            else -> throw IOException("Unsupported extractor HTTP method")
        }
        val baseRequest = builder.build()
        val cookieHeader = CookieHeaderCodec.merge(
            baseRequest.headers("Cookie"),
            session.cookiesFor(baseRequest.url),
        )
        val requestWithCookies = if (cookieHeader == null) {
            baseRequest
        } else {
            baseRequest.newBuilder().header("Cookie", cookieHeader).build()
        }
        client.newCall(requestWithCookies).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }
}
