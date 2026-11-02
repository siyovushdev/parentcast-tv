package dev.siyovush.parentcast.tv.web

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status

internal class ParentHttpServer(
    port: Int,
    private val controller: ApiController,
) : NanoHTTPD("0.0.0.0", port) {
    override fun serve(session: IHTTPSession): Response {
        val request = ApiRequest(
            method = session.method.name,
            path = session.uri,
            headers = session.headers.mapKeys { it.key.lowercase() },
            body = readBody(session),
            clientKey = session.remoteIpAddress,
        )
        val apiResponse = controller.handle(request)
        return newFixedLengthResponse(
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
    }

    private fun readBody(session: IHTTPSession): String = try {
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
