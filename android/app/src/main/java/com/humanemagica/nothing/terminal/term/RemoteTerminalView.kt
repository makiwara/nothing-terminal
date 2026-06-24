package com.humanemagica.nothing.terminal.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.humanemagica.nothing.terminal.net.TerminalClient
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a remote terminal session and forwards input. It reuses Termux's
 * TerminalEmulator (the VT/ANSI engine) and TerminalRenderer (its real cell
 * drawing), but feeds the emulator from the WebSocket instead of a local PTY, and
 * routes the emulator's output to the WebSocket via [WsTerminalOutput]. This is
 * the only viable way to keep the input back-channel intact, since Termux's
 * TerminalSession/TerminalView are final and hard-wired to a local subprocess.
 *
 * Prototype simplifications (flagged for iteration):
 *  - Re-creates the emulator on size change rather than resizing in place (the
 *    server repaints after every SIGWINCH, so no content is lost).
 *  - Minimal key handling (TYPE_NULL + a key bar); no IME composition, no mouse,
 *    no scrollback gestures, no text selection.
 */
class RemoteTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), TerminalClient.Sink {

    private val output = WsTerminalOutput()
    private val sessionClient = MinimalSessionClient()
    private val renderer: TerminalRenderer
    private val cellW: Int
    private val cellH: Int

    private var emulator: TerminalEmulator? = null
    private var cols = 0
    private var rows = 0
    private val pending = ArrayDeque<ByteArray>()

    init {
        val textSizePx = (14f * resources.displayMetrics.scaledDensity).roundToInt()
        renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        cellW = max(1, renderer.fontWidth.roundToInt())
        cellH = max(1, renderer.fontLineSpacing)
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TerminalClient.attachSink(this)
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TerminalClient.detachSink(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Report our capacity; the server applies the smallest-of-all policy and
        // echoes the effective grid size back via onServerSize().
        TerminalClient.sendResize(max(1, w / cellW), max(1, h / cellH))
    }

    override fun onServerSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (emulator != null && cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        // v0.118.0 ctor: (TerminalOutput, columns, rows, transcriptRows, client).
        emulator = TerminalEmulator(output, cols, rows, 2000, sessionClient)
        while (pending.isNotEmpty()) {
            val b = pending.poll()
            emulator!!.append(b, b.size)
        }
        invalidate()
    }

    override fun onBytes(data: ByteArray) {
        val e = emulator
        if (e == null) {
            pending.add(data)
            return
        }
        e.append(data, data.size)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        emulator?.let { renderer.render(it, canvas, 0, -1, -1, -1, -1) }
    }

    // ---- input ----

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL makes soft keyboards deliver key events we translate to bytes.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send(byteArrayOf(0x7f)) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val seq = keyToBytes(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        send(seq)
        return true
    }

    fun send(data: ByteArray) = TerminalClient.sendInput(data)

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun keyToBytes(keyCode: Int, event: KeyEvent): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0d)
        KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7f)
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1b)
        KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray()
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray()
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray()
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray()
        else -> {
            val ch = event.unicodeChar
            when {
                ch == 0 -> null
                event.isCtrlPressed -> byteArrayOf((ch and 0x1f).toByte()) // Ctrl-<key>
                else -> String(Character.toChars(ch)).toByteArray(Charsets.UTF_8)
            }
        }
    }
}
