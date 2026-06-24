package com.humanemagica.nothing.terminal.term

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * The TerminalEmulator constructor requires a TerminalSessionClient for logging,
 * cursor style, and lifecycle callbacks. We don't use TerminalSession at all (it
 * spawns a local subprocess and routes the emulator's responses to it instead of
 * to the network), so these are mostly no-ops. The emulator never actually passes
 * a non-null TerminalSession to most of these in our usage.
 */
class MinimalSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "term", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "term", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "term", message ?: "") }
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.w(tag ?: "term", message, e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.w(tag ?: "term", e) }
}
