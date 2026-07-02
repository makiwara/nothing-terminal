package com.humanemagica.nothing.terminal.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * One WebSocket to a single session's data plane (specs/protocol.md). The bound
 * [Sink] (the terminal view) receives output bytes and effective-size frames on
 * the main thread; the view sends resize frames and the emulator's back-channel
 * bytes back. One per visible ring page; closed when the page leaves the window.
 */
class SessionConnection(private val wsUrl: String, private val token: String) {

    interface Sink {
        fun onBytes(data: ByteArray)
        fun onServerSize(cols: Int, rows: Int)
        /** The data plane dropped (or never delivered). No reconnect follows. */
        fun onFailure()
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var sink: Sink? = null

    fun connect(sink: Sink) {
        this.sink = sink
        val sep = if (wsUrl.contains("?")) "&" else "?"
        val url = if (token.isNotBlank()) "$wsUrl${sep}token=$token" else wsUrl
        val req = Request.Builder().url(url).apply {
            if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
        }.build()
        ws = http.newWebSocket(req, listener)
    }

    fun close() {
        sink = null
        ws?.close(1000, "bye")
        ws = null
    }

    /** Input bytes: the emulator's back-channel responses (and, if ever, keys). */
    fun sendInput(data: ByteArray) {
        ws?.send(data.toByteString())
    }

    fun sendResize(cols: Int, rows: Int) {
        ws?.send(Protocol.resizeFrame(cols, rows))
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val size = Protocol.parseSize(text) ?: return
            main.post { sink?.onServerSize(size.first, size.second) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val arr = bytes.toByteArray()
            main.post { sink?.onBytes(arr) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("SessionConnection", "ws failure for $wsUrl", t)
            main.post { sink?.onFailure() }
        }
    }
}
