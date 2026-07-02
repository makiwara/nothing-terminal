package com.humanemagica.nothing.terminal.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.humanemagica.nothing.terminal.net.CockpitModel
import com.humanemagica.nothing.terminal.net.Review
import com.humanemagica.nothing.terminal.net.Script
import com.humanemagica.nothing.terminal.net.Session
import com.humanemagica.nothing.terminal.term.RemoteTerminalView
import com.humanemagica.nothing.terminal.ui.theme.Cyan

@Composable
fun CockpitScreen() {
    val state by CockpitModel.state.collectAsState()
    when (val s = state) {
        is CockpitModel.State.Loading -> Centered { Text("Connecting…", color = Cyan.ink) }
        is CockpitModel.State.Failed -> Centered {
            Text("Failed: ${s.message}", color = Cyan.ink)
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
    val notice by CockpitModel.notice.collectAsState()
    val focus by CockpitModel.focus.collectAsState()
    val pageCount = ring.size + 1 // page 0 is the manager; terminals follow
    val pager = rememberPagerState { pageCount }
    var showSelector by remember { mutableStateOf(false) }
    val currentId = if (pager.currentPage == 0) null else ring.getOrNull(pager.currentPage - 1)?.id

    LaunchedEffect(currentId) { CockpitModel.activeSessionId = currentId }

    // A freshly opened terminal joins the ring; swing the pager onto it.
    LaunchedEffect(focus, ring) {
        val id = focus ?: return@LaunchedEffect
        val idx = ring.indexOfFirst { it.id == id }
        if (idx >= 0) {
            pager.animateScrollToPage(idx + 1)
            CockpitModel.focusHandled()
        }
    }

    Box(Modifier.fillMaxSize().background(Cyan.bg)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                ManagerPage(
                    ring = ring,
                    pageCount = pageCount,
                    currentPage = pager.currentPage,
                    onHalt = { CockpitModel.close(it) },
                    onAdd = { showSelector = true },
                )
            } else {
                ring.getOrNull(page - 1)?.let { session ->
                    key(session.id) { TerminalPage(session) }
                }
            }
        }

        // Voice: only on a terminal page, and not while an overlay is up.
        if (currentId != null && !transcribing && review == null && !showSelector) {
            RecorderLayer(CockpitModel.recorder)
        }

        if (showSelector) {
            PresetSelector(
                scripts = scripts,
                onPick = { CockpitModel.open(it); showSelector = false },
                onCancel = { showSelector = false },
            )
        }
        if (transcribing) TranscribingOverlay()
        review?.let { ReviewOverlay(it) }
        notice?.let { NoticeBanner(it) }
    }
}

/** A transient message pinned to the top (e.g. "home offline" on a failed open). */
@Composable
private fun BoxScope.NoticeBanner(message: String) {
    LaunchedEffect(message) { delay(3000); CockpitModel.dismissNotice() }
    Box(
        Modifier.align(Alignment.TopCenter).padding(12.dp).clickable { CockpitModel.dismissNotice() }
            .background(Cyan.danger).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(message, color = Cyan.bg, fontSize = 12.sp) }
}

/** A terminal page: a small muted title (with a running dot) over the cell grid. No other chrome. */
@Composable
private fun TerminalPage(session: Session) {
    val exited = session.state == "exited"
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (exited) Cyan.danger else Cyan.accent))
            Spacer(Modifier.width(6.dp))
            Text(session.label, color = Cyan.muted, fontSize = 12.sp)
        }
        if (exited) {
            val code = session.exitCode?.let { " (code $it)" }.orEmpty()
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("session exited$code", color = Cyan.muted, fontSize = 13.sp)
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    RemoteTerminalView(ctx, CockpitModel.wsUrl(session.id), CockpitModel.token()) {
                        CockpitModel.showNotice("${session.label} disconnected")
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** The dedicated ring page that lists open terminals, halts each, and opens the preset selector. */
@Composable
private fun ManagerPage(
    ring: List<Session>,
    pageCount: Int,
    currentPage: Int,
    onHalt: (String) -> Unit,
    onAdd: () -> Unit,
) {
    // A 1s tick so running sessions' ages advance without waiting for a ring refresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        InvertedHeader("OPEN TERMINALS")
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 100.dp).verticalScroll(rememberScrollState())) {
            if (ring.isEmpty()) {
                Text("No open sessions", color = Cyan.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 9.dp))
            } else {
                ring.forEach { s ->
                    val running = s.state != "exited"
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) Cyan.accent else Cyan.faint))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, color = Cyan.ink, fontSize = 13.sp)
                            sessionMeta(s, running, now)?.let { Text(it, color = Cyan.muted, fontSize = 11.sp) }
                        }
                        Box(
                            Modifier.border(1.dp, Cyan.muted).clickable { onHalt(s.id) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) { Text("Halt", color = Cyan.muted, fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = Cyan.rule)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 100.dp, vertical = 14.dp).border(1.dp, Cyan.accent).clickable { onAdd() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) { Text("+ Add new terminal", color = Cyan.accent, fontSize = 15.sp) }
        RingDots(pageCount, currentPage)
    }
}

/** Row subtitle: running → uptime from started_at; exited → why it ended (the v1 exit_reason). */
private fun sessionMeta(s: Session, running: Boolean, nowMs: Long): String? {
    if (!running) {
        return when (s.exitReason) {
            "host_restarted" -> "host restarted"
            "halted" -> "halted"
            "child_exited" -> s.exitCode?.let { "exited · code $it" } ?: "exited"
            else -> "exited"
        }
    }
    if (s.startedAt.isEmpty()) return null
    return runCatching {
        val secs = ((nowMs - java.time.Instant.parse(s.startedAt).toEpochMilli()) / 1000).coerceAtLeast(0)
        when {
            secs < 60 -> "up ${secs}s"
            secs < 3600 -> "up ${secs / 60}m"
            else -> "up ${secs / 3600}h${(secs % 3600) / 60}m"
        }
    }.getOrNull()
}

/** The preset selector: a separate full screen. Pick a preset → open a panel in the ring. */
@Composable
private fun PresetSelector(scripts: List<Script>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Cyan.bg)) {
        Column(Modifier.fillMaxSize()) {
            InvertedHeader("Add terminal")
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 100.dp)) {
                scripts.forEach { sc ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(sc.id) }.padding(vertical = 9.dp)) {
                        Text(sc.label, color = Cyan.accent, fontSize = 13.sp)
                        if (sc.command.isNotEmpty()) {
                            Text(sc.command, color = Cyan.muted, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = Cyan.rule)
                }
                Column(Modifier.fillMaxWidth().clickable { onCancel() }.padding(vertical = 9.dp)) {
                    Text("Cancel", color = Cyan.danger, fontSize = 13.sp)
                }
                HorizontalDivider(color = Cyan.rule)
            }
        }
    }
}

@Composable
private fun InvertedHeader(text: String) {
    Box(Modifier.fillMaxWidth().background(Cyan.accent).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Cyan.bg, fontSize = 12.sp)
    }
}

@Composable
private fun RingDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == current) Cyan.accent else Cyan.faint))
        }
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
                modifier = Modifier.fillMaxWidth().weight(1f).background(Cyan.accentSoft).padding(16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewButton("Cancel", Cyan.danger, Modifier.weight(1f)) { CockpitModel.cancelReview() }
                ReviewButton("Adjust", Cyan.accent, Modifier.weight(1f)) { CockpitModel.adjust(edited) }
                ReviewButton("Confirm", Cyan.bg, Modifier.weight(1f), fill = Cyan.accent) { CockpitModel.confirm(edited) }
            }
        }
    }
}

@Composable
private fun ReviewButton(label: String, color: Color, modifier: Modifier, fill: Color? = null, onClick: () -> Unit) {
    Box(
        modifier
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(1.dp, color))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = 15.sp) }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Cyan.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
