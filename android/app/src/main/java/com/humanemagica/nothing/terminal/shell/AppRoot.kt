package com.humanemagica.nothing.terminal.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.humanemagica.nothing.terminal.BuildConfig
import com.humanemagica.nothing.terminal.net.CockpitModel

/**
 * Standalone-app shell: start the control-plane model on launch, then host the
 * cockpit. Dropped on merge into nothing-to-say (which hosts the cockpit as a
 * panel instead).
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        CockpitModel.start(context, BuildConfig.TERMINALS_BASE_URL, BuildConfig.TERMINALS_DEVICE_TOKEN)
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        CockpitScreen()
    }
}
