package com.humanemagica.nothing.terminal.term

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val ESC = "\u001b"

/** The terminal surface (Termux-backed) plus a small key bar for keys a soft
 *  keyboard can't send. */
@Composable
fun TerminalScreen() {
    var view by remember { mutableStateOf<RemoteTerminalView?>(null) }
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> RemoteTerminalView(ctx).also { view = it } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        KeyBar(onKey = { bytes -> view?.send(bytes) }, onKeyboard = { view?.showKeyboard() })
    }
}

@Composable
private fun KeyBar(onKey: (ByteArray) -> Unit, onKeyboard: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip("esc") { onKey(byteArrayOf(0x1b)) }
        Chip("tab") { onKey(byteArrayOf(0x09)) }
        Chip("↑") { onKey("$ESC[A".toByteArray()) }
        Chip("↓") { onKey("$ESC[B".toByteArray()) }
        Chip("←") { onKey("$ESC[D".toByteArray()) }
        Chip("→") { onKey("$ESC[C".toByteArray()) }
        Spacer(Modifier.weight(1f))
        Chip("⌨") { onKeyboard() }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        border = AssistChipDefaults.assistChipBorder(enabled = true),
    )
}
