package com.humanemagica.nothing.terminal.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.humanemagica.nothing.terminal.net.CockpitModel
import com.humanemagica.nothing.terminal.net.Review
import com.humanemagica.nothing.terminal.term.RemoteTerminalView
import com.humanemagica.nothing.terminal.ui.theme.Cyan

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
    val transcribing by CockpitModel.transcribing.collectAsState()
    val pager = rememberPagerState { ring.size }
    var menuOpen by remember { mutableStateOf(false) }
    val currentId = ring.getOrNull(pager.currentPage)?.id

    LaunchedEffect(currentId) { CockpitModel.activeSessionId = currentId }

    Box(Modifier.fillMaxSize().background(Cyan.bg)) {
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

        // Voice: the slide-up recorder drives the vendored controller; on Send it uploads to
        // propose, and the transcribe/review overlays below take over (so the handle is idle then).
        if (currentId != null && !transcribing && review == null) {
            RecorderLayer(CockpitModel.recorder)
        }

        // Top-right menu: close active / open new. (Ring-manager rework is the next step; the
        // menu stands in for it until then.)
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

        if (transcribing) TranscribingOverlay()
        review?.let { ReviewOverlay(it) }
    }
}

/** Quiet, non-inverted "transcribing" surface while the brain proposes (specs/ui/review.md). */
@Composable
private fun TranscribingOverlay() {
    Box(Modifier.fillMaxSize().background(Cyan.accentSoft), contentAlignment = Alignment.Center) {
        Text("transcribing…", color = Cyan.muted, fontSize = 14.sp)
    }
}

/** The proposed command, editable; Cancel / Adjust / Confirm. Only Confirm injects. */
@Composable
private fun ReviewOverlay(r: Review) {
    var edited by remember(r) { mutableStateOf(r.transcript) }
    Box(Modifier.fillMaxSize().background(Cyan.bg)) {
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = edited,
                onValueChange = { edited = it },
                textStyle = TextStyle(color = Cyan.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Cyan.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Cyan.accentSoft)
                    .padding(16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReviewButton("Cancel", Cyan.danger, Modifier.weight(1f)) { CockpitModel.cancelReview() }
                ReviewButton("Adjust", Cyan.accent, Modifier.weight(1f)) { CockpitModel.cancelReview() }
                ReviewButton("Confirm", Cyan.bg, Modifier.weight(1f), fill = Cyan.accent) {
                    CockpitModel.confirm(edited)
                }
            }
        }
    }
}

@Composable
private fun ReviewButton(
    label: String,
    color: Color,
    modifier: Modifier,
    fill: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(1.dp, color))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 15.sp)
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Cyan.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
