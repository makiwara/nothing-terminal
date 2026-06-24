package com.humanemagica.nothing.terminal.term

import com.humanemagica.nothing.terminal.net.TerminalClient
import com.termux.terminal.TerminalOutput

/**
 * The emulator's output sink. The TerminalEmulator writes its host-bound bytes
 * here — terminal query responses (DA replies, cursor-position reports), mouse
 * events, bracketed-paste markers — and we forward them over the WebSocket. This
 * is what preserves the bidirectional back-channel: the remote app's
 * interrogations get answered, not just human keystrokes.
 */
class WsTerminalOutput : TerminalOutput() {
    override fun write(data: ByteArray, offset: Int, count: Int) {
        TerminalClient.sendInput(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() {}
}
