package com.humanemagica.nothing.terminal.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.humanemagica.nothing.terminal.net.CockpitModel
import com.humanemagica.nothing.terminal.net.Review
import com.humanemagica.nothing.terminal.term.RemoteTerminalView

@Composable
fun CockpitScreen() {
    val state by CockpitModel.state.collectAsState()
    when (val s = state) {
        is CockpitModel.State.Loading -> Centered { Text("Connecting…", color = Color.White) }
        is CockpitModel.State.Failed -> Centered {
            Text("Failed: ${s.message}", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { CockpitModel.refresh() }) { Text("Retry") }
        }
        is CockpitModel.State.Ready -> Cockpit()
    }
}

@Composable
private fun Cockpit() {
    val ring by CockpitModel.ring.collectAsState()
    val scripts by CockpitModel.scripts.collectAsState()
    val review by CockpitModel.review.collectAsState()
    val pager = rememberPagerState { ring.size }
    var recording by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val currentId = ring.getOrNull(pager.currentPage)?.id

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (ring.isEmpty()) {
            Centered { Text("No open sessions — open one from the menu", color = Color.White) }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val session = ring[page]
                key(session.id) {
                    AndroidView(
                        factory = { ctx ->
                            RemoteTerminalView(ctx, CockpitModel.wsUrl(session.id), CockpitModel.token())
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Top-right menu: close active / open new.
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Text("☰", color = Color.White, modifier = Modifier.clickable { menuOpen = true }.padding(12.dp))
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (currentId != null) {
                    DropdownMenuItem(
                        text = { Text("Close active terminal") },
                        onClick = { menuOpen = false; CockpitModel.close(currentId) },
                    )
                    HorizontalDivider()
                }
                Text("Open new", color = Color.Gray, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                scripts.forEach { sc ->
                    DropdownMenuItem(
                        text = { Text(sc.label) },
                        onClick = { menuOpen = false; CockpitModel.open(sc.id) },
                    )
                }
            }
        }

        // Voice: hold the handle to record; release to propose. (Stub recorder —
        // no real audio; the stand-in's mock STT ignores it. Replaced by
        // nothing-to-say's RecordingController on merge.)
        if (currentId != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (recording) Color(0xFF2E7D32) else Color(0xFF333333),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .pointerInput(currentId) {
                        detectTapGestures(onPress = {
                            recording = true
                            tryAwaitRelease()
                            recording = false
                            CockpitModel.propose(currentId, null)
                        })
                    },
            ) {
                Text(
                    if (recording) "● recording — release to send" else "hold to speak",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        review?.let { ReviewOverlay(it) }
    }
}

@Composable
private fun ReviewOverlay(r: Review) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF1E1E1E)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.transcript, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { CockpitModel.cancelReview() }) { Text("Cancel") }
                    TextButton(onClick = { CockpitModel.cancelReview() }) { Text("Adjust") }
                    Button(onClick = { CockpitModel.sendReviewed() }, enabled = r.action != null) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
