package com.gbscontrol.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: ByteArray) {
    val successful: Boolean get() = code in 200..299
    fun text(): String = body.toString(Charsets.UTF_8)
}

/** HTTP commands and the live state socket. */
class GbsClient {
    private val webSocketClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var webSocket: WebSocket? = null

    suspend fun get(host: String, path: String): HttpResult = request(host, path, "GET", null)

    suspend fun postForm(host: String, path: String, values: Map<String, String>): HttpResult {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${key.formEncode()}=${value.formEncode()}"
        }.toByteArray()
        return request(host, path, "POST", body)
    }

    private suspend fun request(host: String, path: String, method: String, body: ByteArray?): HttpResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(HostAddress.httpUrl(host, path)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = method
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.1")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                        setFixedLengthStreamingMode(body.size)
                    }
                }
                if (body != null) connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                HttpResult(code, stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                connection?.disconnect()
            }
        }

    fun openStateSocket(
        host: String,
        onOpen: () -> Unit,
        onState: (DeviceState) -> Unit,
        onClosed: (String?) -> Unit,
    ) {
        closeStateSocket()
        val request = Request.Builder()
            .url(HostAddress.webSocketUrl(host))
            .header("Sec-WebSocket-Protocol", "arduino")
            .build()
        webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                LegacyStateParser.parse(text)?.let(onState)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                LegacyStateParser.parse(bytes.string(Charsets.ISO_8859_1))?.let(onState)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(reason)

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) =
                onClosed(error.message)
        })
    }

    fun closeStateSocket() {
        webSocket?.close(1000, "switching device")
        webSocket = null
    }

    fun shutdown() {
        closeStateSocket()
        webSocketClient.dispatcher.executorService.shutdown()
        webSocketClient.connectionPool.evictAll()
    }

    private fun String.formEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    companion object {
        private const val TIMEOUT_MS = 3500
    }
}
