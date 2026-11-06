package dev.siyovush.parentcast.tv.web

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status

internal class ParentHttpServer(
    port: Int,
    private val controller: ApiController,
) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession): Response {
        if(session.uri.startsWith("/api/local/upload/") || session.uri=="/api/covers") {
            val request=ApiRequest(session.method.name,session.uri,session.headers.mapKeys { it.key.lowercase() },"",session.remoteIpAddress)
            val result=controller.upload(request,session.headers["content-length"]?.toLongOrNull() ?: -1,session.inputStream)
            return respond(result).apply { closeConnection(true) }
        }
        val tooLarge = session.headers["content-length"]?.toLongOrNull()?.let { it < 0 || it > 65_536 } == true
        val request = ApiRequest(
            method = session.method.name,
            path = session.uri,
            headers = session.headers.mapKeys { it.key.lowercase() },
            body = if (tooLarge) "" else readBody(session),
            clientKey = session.remoteIpAddress,
        )
        val apiResponse = if (tooLarge) ApiResponse(413, "{\"error\":\"BODY_TOO_LARGE\"}") else controller.handle(request)
        return respond(apiResponse)
    }
    private fun respond(apiResponse: ApiResponse): Response = newFixedLengthResponse(
            responseStatus(apiResponse.code),
            apiResponse.contentType,
            apiResponse.body,
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("X-Frame-Options", "DENY")
            addHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
                    "img-src 'self' data:; frame-ancestors 'none'; form-action 'self'; base-uri 'self'",
            )
        }

    private fun readBody(session: IHTTPSession): String = try {
        // JSON uses UTF-8; NanoHTTPD 2.3.1 otherwise defaults missing charset to US-ASCII.
        // Keep its HTTP/body parser and only supply the JSON encoding it should use.
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.equals("application/json", true) == true) {
            session.headers["content-type"] = "application/json; charset=utf-8"
        }
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        body["postData"].orEmpty()
    } catch (error: Exception) {
        ""
    }
}

internal fun responseStatus(code: Int): NanoHTTPD.Response.IStatus = when (code) {
    421 -> MisdirectedRequestStatus
    else -> Status.lookup(code) ?: Status.INTERNAL_ERROR
}

private object MisdirectedRequestStatus : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = 421
    override fun getDescription(): String = "421 Misdirected Request"
}
