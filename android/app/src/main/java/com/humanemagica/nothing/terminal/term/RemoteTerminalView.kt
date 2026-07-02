package com.humanemagica.nothing.terminal.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.humanemagica.nothing.terminal.net.SessionConnection
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders one remote session and forwards the emulator back-channel. Reuses
 * Termux's TerminalEmulator (VT/ANSI engine) and TerminalRenderer (cell drawing)
 * inside a plain View, fed from a SessionConnection. No keyboard — input is voice
 * only. Vertical drag scrolls scrollback; horizontal drags are left for the
 * Compose pager (the ring).
 */
@SuppressLint("ViewConstructor")
class RemoteTerminalView(
    context: Context,
    private val wsUrl: String,
    private val token: String,
    private val onDisconnect: () -> Unit,
) : View(context), SessionConnection.Sink {

    private val renderer: TerminalRenderer
    private val cellW: Int
    private val cellH: Int
    private val sessionClient = MinimalSessionClient()

    private var conn: SessionConnection? = null
    private var output: WsTerminalOutput? = null
    private var emulator: TerminalEmulator? = null
    private var cols = 0
    private var rows = 0
    private var topRow = 0 // 0 = bottom (live); negative scrolls into transcript
    private val pending = ArrayDeque<ByteArray>()

    private val scroll = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Only act on predominantly-vertical drags; let the pager have horizontal.
            if (abs(dy) <= abs(dx)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            val deltaRows = (dy / cellH).roundToInt()
            if (deltaRows != 0) scrollBy(deltaRows)
            return true
        }
    })

    init {
        val textSizePx = (14f * resources.displayMetrics.scaledDensity).roundToInt()
        renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        cellW = max(1, renderer.fontWidth.roundToInt())
        cellH = max(1, renderer.fontLineSpacing)
        setBackgroundColor(Color.BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val c = SessionConnection(wsUrl, token)
        conn = c
        output = WsTerminalOutput(c)
        c.connect(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        conn?.close()
        conn = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        conn?.sendResize(max(1, w / cellW), max(1, h / cellH))
    }

    override fun onServerSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (emulator != null && cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        topRow = 0
        // v0.118.0 ctor: (TerminalOutput, columns, rows, transcriptRows, client).
        emulator = TerminalEmulator(output, cols, rows, 2000, sessionClient)
        while (pending.isNotEmpty()) {
            val b = pending.poll()
            emulator!!.append(b, b.size)
        }
        invalidate()
    }

    override fun onFailure() = onDisconnect()

    override fun onBytes(data: ByteArray) {
        val e = emulator
        if (e == null) {
            pending.add(data)
            return
        }
        e.append(data, data.size)
        invalidate()
    }

    private fun scrollBy(deltaRows: Int) {
        val e = emulator ?: return
        val minTop = -e.screen.activeTranscriptRows
        topRow = (topRow + deltaRows).coerceIn(minTop, 0)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return scroll.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        emulator?.let { renderer.render(it, canvas, topRow, -1, -1, -1, -1) }
    }
}
