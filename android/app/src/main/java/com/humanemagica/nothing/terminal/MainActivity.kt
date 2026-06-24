package com.humanemagica.nothing.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.humanemagica.nothing.terminal.shell.AppRoot
import com.humanemagica.nothing.terminal.ui.theme.TerminalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TerminalTheme { AppRoot() } }
    }
}
