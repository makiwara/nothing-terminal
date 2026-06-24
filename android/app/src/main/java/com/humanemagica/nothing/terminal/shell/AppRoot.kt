package com.humanemagica.nothing.terminal.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.humanemagica.nothing.terminal.BuildConfig
import com.humanemagica.nothing.terminal.net.TerminalClient
import com.humanemagica.nothing.terminal.term.TerminalScreen

/**
 * Standalone-app shell: auto-connect on launch, show the terminal when connected,
 * otherwise a minimal status/retry screen. (When lifted into nothing-to-say this
 * file is dropped; TerminalScreen is hosted as a panel instead.)
 */
@Composable
fun AppRoot() {
    val conn by TerminalClient.conn.collectAsState()

    LaunchedEffect(Unit) {
        TerminalClient.connect(BuildConfig.TERMINALS_WS_URL, BuildConfig.TERMINALS_DEVICE_TOKEN)
    }

    Surface(Modifier.fillMaxSize()) {
        when (val c = conn) {
            is TerminalClient.Conn.Connected -> TerminalScreen()
            is TerminalClient.Conn.Failed -> Status("Failed: ${c.message}", showConnect = true)
            TerminalClient.Conn.Connecting -> Status("Connecting…", showConnect = false)
            TerminalClient.Conn.Idle -> Status("Idle", showConnect = true)
        }
    }
}

@Composable
private fun Status(text: String, showConnect: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        if (showConnect) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                TerminalClient.connect(BuildConfig.TERMINALS_WS_URL, BuildConfig.TERMINALS_DEVICE_TOKEN)
            }) { Text("Connect") }
        }
    }
}
