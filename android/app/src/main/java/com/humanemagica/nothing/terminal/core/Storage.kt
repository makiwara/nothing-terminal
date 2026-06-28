package com.humanemagica.nothing.terminal.core

import android.content.Context
import java.io.File

// Shim for nothing-to-say's core.Storage: the vendored voice/ engine asks only for the recording
// directory. Recordings are transient — captured, uploaded to the propose call, then deleted — so
// the cache dir is the right home.
object Storage {
    fun recordingDir(context: Context): File = File(context.cacheDir, "recordings")
}
