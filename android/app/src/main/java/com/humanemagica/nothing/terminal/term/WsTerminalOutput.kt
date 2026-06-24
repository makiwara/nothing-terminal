package com.humanemagica.nothing.terminal.term

import com.humanemagica.nothing.terminal.net.SessionConnection
import com.termux.terminal.TerminalOutput

/**
 * The emulator's output sink. The emulator writes its host-bound bytes here —
 * terminal query responses (DA replies, cursor reports) — and we forward them
 * over this session's WebSocket, preserving the bidirectional back-channel. User
 * commands do not flow through here; they arrive via the voice send endpoint.
 */
class WsTerminalOutput(private val conn: SessionConnection) : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {
        conn.sendInput(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() {}
}
