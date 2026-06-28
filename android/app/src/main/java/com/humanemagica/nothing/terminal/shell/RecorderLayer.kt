package com.humanemagica.nothing.terminal.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humanemagica.nothing.terminal.ui.theme.Cyan
import com.humanemagica.nothing.terminal.voice.RecordingController
import com.humanemagica.nothing.terminal.voice.RecordingController.State

/**
 * The native landscape recording surface, driving the vendored [RecordingController]. The bottom
 * handle arms on press and maps the finger's vertical position to the controller's `top` fraction;
 * the controller decides arm → held → locked / options. Inverted palette (accent fill, black
 * content) per specs/ui/voice_input.md. First cut of the choreography — the gesture feel and the
 * mount/lock flashes still want device tuning.
 */
@Composable
fun RecorderLayer(controller: RecordingController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val elapsed by controller.elapsedSec.collectAsState()
    val exit by controller.exit.collectAsState()
    LaunchedEffect(exit) { if (exit != RecordingController.Exit.None) controller.clearExit() }

    var boxH by remember { mutableFloatStateOf(1f) }
    var handleTop by remember { mutableFloatStateOf(0f) }

    Box(modifier.fillMaxSize().onGloballyPositioned { boxH = it.size.height.toFloat() }) {
        if (state != State.Idle) {
            CaptureSurface(state, elapsed, controller)
        }

        // The pull target: always present; a press arms, the drag drives the controller.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .onGloballyPositioned { handleTop = it.positionInParent().y }
                .pointerInput(controller) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        controller.arm()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val absY = handleTop + change.position.y
                            controller.updateDrag((absY / boxH).coerceIn(0f, 1f))
                            change.consume()
                            if (!change.pressed) { controller.release(); break }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(56.dp).height(3.dp).background(Cyan.accent.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun CaptureSurface(state: State, elapsed: Int, controller: RecordingController) {
    Column(Modifier.fillMaxSize().background(Cyan.bg)) {
        if (state == State.Options) Buttons(controller)
        Block(elapsed)
        if (state == State.Locked) Buttons(controller)
        val free = if (state == State.Locked) Cyan.accentLock else Cyan.accentPanel
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(free)
                .weight(1f, fill = true),
        )
    }
}

@Composable
private fun Block(elapsed: Int) {
    Column(Modifier.fillMaxWidth().height(96.dp).background(Cyan.accent)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(10.dp).clip(RectangleShape).background(Cyan.bg))
            Text(formatElapsed(elapsed), color = Cyan.bg, fontSize = 13.sp)
        }
        Waveform(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp))
    }
}

@Composable
private fun Buttons(controller: RecordingController) {
    Row(
        Modifier.fillMaxWidth().background(Cyan.accent).padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecButton("Cancel", Modifier.weight(1f)) { controller.tapCancel() }
        RecButton("Send", Modifier.weight(1f)) { controller.tapSend() }
    }
}

@Composable
private fun RecButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = Cyan.bg,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .border(1.dp, Cyan.bg)
            .pointerInput(label) { awaitEachGesture { awaitFirstDown(); awaitRelease(); onClick() } }
            .padding(vertical = 11.dp),
    )
}

private val WAVE = listOf(14, 26, 18, 30, 14, 24, 16, 28, 20, 26, 18, 24)

@Composable
private fun Waveform(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WAVE.forEachIndexed { i, base ->
            val h = base * (0.55f + 0.45f * (0.5f + 0.5f * kotlin.math.sin(phase + i * 0.6f)))
            Box(Modifier.width(4.dp).height(h.dp).background(Cyan.bg.copy(alpha = 0.85f)))
        }
    }
}

private fun formatElapsed(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitRelease() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.all { !it.pressed }) return
    }
}
