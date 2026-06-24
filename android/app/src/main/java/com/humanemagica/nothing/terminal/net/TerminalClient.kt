package com.humanemagica.nothing.terminal.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * The single WebSocket connection to a termshare-protocol server (the Go stand-in
 * today; nothing-serious `terminals/` later). Implements PROTOCOL.md.
 *
 * A process-wide singleton with StateFlow, matching nothing-to-say's model style.
 * Output and size are delivered to a [Sink] (the terminal view) on the main thread;
 * anything that arrives before a sink attaches is buffered so the late-joiner
 * repaint burst is never lost.
 */
object TerminalClient {

    sealed interface Conn {
        data object Idle : Conn
        data object Connecting : Conn
        data object Connected : Conn
        data class Failed(val message: String) : Conn
    }

    interface Sink {
        fun onBytes(data: ByteArray)
        fun onServerSize(cols: Int, rows: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream, no read timeout
        .build()

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn

    private var ws: WebSocket? = null
    private var sink: Sink? = null
    private val pendingBytes = ArrayDeque<ByteArray>()
    private var pendingSize: Pair<Int, Int>? = null

    fun attachSink(s: Sink) = main.post {
        sink = s
        pendingSize?.let { s.onServerSize(it.first, it.second) }
        while (pendingBytes.isNotEmpty()) s.onBytes(pendingBytes.poll())
    }

    fun detachSink(s: Sink) = main.post { if (sink === s) sink = null }

    fun connect(url: String, token: String) {
        if (_conn.value is Conn.Connecting || _conn.value is Conn.Connected) return
        if (url.isBlank()) {
            _conn.value = Conn.Failed("TERMINALS_WS_URL is not set (local.properties)")
            return
        }
        _conn.value = Conn.Connecting

        // Token both ways: ?token= for the Go stand-in, Bearer header for nothing-serious.
        val finalUrl = if (token.isNotBlank()) {
            val sep = if (url.contains("?")) "&" else "?"
            "$url${sep}token=$token"
        } else url
        val req = Request.Builder().url(finalUrl).apply {
            if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
        }.build()
        ws = http.newWebSocket(req, listener)
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        _conn.value = Conn.Idle
    }

    /** Input bytes: keystrokes AND emulator-generated responses (DA / cursor / mouse). */
    fun sendInput(data: ByteArray) {
        ws?.send(data.toByteString())
    }

    fun sendResize(cols: Int, rows: Int) {
        ws?.send(Protocol.resizeFrame(cols, rows))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) =
            main.post { _conn.value = Conn.Connected }.let {}

        override fun onMessage(webSocket: WebSocket, text: String) {
            val size = Protocol.parseSize(text) ?: return
            main.post {
                val s = sink
                if (s != null) s.onServerSize(size.first, size.second) else pendingSize = size
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val arr = bytes.toByteArray()
            main.post {
                val s = sink
                if (s != null) s.onBytes(arr) else pendingBytes.add(arr)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("TerminalClient", "ws failure", t)
            main.post { _conn.value = Conn.Failed(t.message ?: "connection failed") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            main.post { if (_conn.value !is Conn.Failed) _conn.value = Conn.Idle }.let {}
    }
}
