package com.humanemagica.nothing.terminal.core

import android.util.Log

// Shim for nothing-to-say's core.NtsLog: the vendored voice/ engine logs through this.
// The cockpit doesn't need the in-app ring buffer the original keeps, so route to logcat only.
object NtsLog {
    private const val TAG = "Nts"
    fun i(message: String) { Log.i(TAG, message) }
    fun w(message: String, t: Throwable? = null) { if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message) }
    fun e(message: String, t: Throwable? = null) { if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message) }
}
