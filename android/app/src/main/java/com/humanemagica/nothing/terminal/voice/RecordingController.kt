// Vendored from nothing-to-say@8488823 (voice/RecordingController.kt). Faithful copy — re-sync,
// don't diverge. Only the package and the core.* imports are repackaged for this repo; the
// cockpit's divergence (the propose/send sink, the after-send transcribe/review) lives in the
// caller-supplied onSend and in the native surface, not in this file.
package com.humanemagica.nothing.terminal.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import com.humanemagica.nothing.terminal.core.NtsLog
import com.humanemagica.nothing.terminal.core.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val MIN_SEND_SECONDS = 1

/**
 * Drives the pull-to-unwrap recording surface for one panel: arm (no capture) → mount (capture
 * starts) → held (release sends) → locked / options (hands-free, button-driven). Owns the
 * OpusRecorder; capture runs on IO. The completed recording is handed to [onSend] (the chat
 * voice-note dispatch); the sink then owns the file's lifecycle.
 * See specs/gestures.md and mockups/recording.html.
 */
class RecordingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSend: suspend (path: String, durationSec: Int) -> Unit,
) {
    companion object {
        // Finger position as a fraction of screen height (0 = top, 1 = bottom).
        const val REVEAL_START = 0.50f // recording block starts fading in as the pull passes here
        const val LOCK_AT = 0.03f      // pulled to the very top → flash → hands-free Locked
        const val OPTIONS_AT = 0.75f   // pulled back down this far → hands-free Options
        const val UNOPTION_AT = 0.50f  // from Options, rising above here returns to Held
    }

    // Profile-scoped activation zone, supplied by the surface from NtsDims. Defaults match
    // the AEKmini design (0.25) so a controller used before configuration is unsurprising.
    var mountAt: Float = 0.25f   // block fully shown → flash → capture starts
    var unlockAt: Float = 0.25f  // from Locked, dropping below here un-locks (hysteresis)

    enum class State { Idle, Arming, Held, Locked, Options }

    /** How the surface should leave the screen once we return to Idle. Cleared by the surface
     *  after it finishes the exit animation. */
    enum class Exit { None, SlideDown, Flash }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _exit = MutableStateFlow(Exit.None)
    val exit: StateFlow<Exit> = _exit.asStateFlow()

    fun clearExit() { _exit.value = Exit.None }

    private val _top = MutableStateFlow(1f) // finger position, fraction of the pull area (0 top, 1 bottom)
    val top: StateFlow<Float> = _top.asStateFlow()

    // Pixels from the screen top excluded from the pull (the chat-name header sits here and stays
    // visible); both the gesture math and the surface treat this as the canvas top.
    private val _topInset = MutableStateFlow(0f)
    val topInset: StateFlow<Float> = _topInset.asStateFlow()

    fun setTopInset(px: Float) { _topInset.value = px }

    // Top edge (px, screen coords) of the bottom drag handle — the gesture lives on the handle,
    // so it maps the finger's screen position from this origin.
    private val _handleTop = MutableStateFlow(0f)
    val handleTop: StateFlow<Float> = _handleTop.asStateFlow()

    fun setHandleTop(px: Float) { _handleTop.value = px }

    // Full screen height (px), so the handle-local gesture can express the panel position as a
    // screen-height fraction.
    private val _screenHeight = MutableStateFlow(0f)
    val screenHeight: StateFlow<Float> = _screenHeight.asStateFlow()

    fun setScreenHeight(px: Float) { _screenHeight.value = px }

    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()

    private var recorder: OpusRecorder? = null
    private var timerJob: Job? = null
    @Volatile private var pending = Pending.None

    private enum class Pending { None, Send, Cancel }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // Exclusive transient focus: tells the OS to silence other apps' notification sounds and
    // ducks our own channel for the duration, so they can't bleed into the OGG. If focus is
    // taken from us (call, alarm, voice nav) the recording is already compromised — discard.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                NtsLog.i("audio focus lost ($change), discarding recording")
                discardOnFocusLoss()
            }
        }
        .build()
    @Volatile private var focusHeld = false

    fun arm() {
        if (_state.value == State.Idle) {
            _exit.value = Exit.None
            _state.value = State.Arming
            _top.value = 1f
        }
    }

    /** Live finger update while pressed. [top] is the finger's screen-height fraction (0 top,
     *  1 bottom). Arming mounts (capture starts) once the pull reaches MOUNT_AT. Once mounted,
     *  the very top glues to Locked (un-glues only below UNLOCK_AT) and pulling back down past
     *  OPTIONS_AT commits Options (un-commits only above UNOPTION_AT). */
    fun updateDrag(top: Float) {
        _top.value = top
        when (_state.value) {
            State.Arming -> if (top <= mountAt) mount()
            State.Held -> _state.value = when {
                top <= LOCK_AT -> State.Locked
                top >= OPTIONS_AT -> State.Options
                else -> State.Held
            }
            State.Locked -> if (top > unlockAt) _state.value = State.Held
            State.Options -> if (top < UNOPTION_AT) _state.value = State.Held
            else -> Unit
        }
    }

    /** Finger lifted during the arm-and-hold drag. */
    fun release() {
        when (_state.value) {
            State.Arming -> { _exit.value = Exit.SlideDown; reset() } // released before mounting
            State.Held -> finish(Pending.Send)
            else -> Unit // Locked / Options are button-driven
        }
    }

    fun tapSend() {
        if (_state.value == State.Locked || _state.value == State.Options) finish(Pending.Send)
    }

    fun tapCancel() {
        if (_state.value == State.Locked || _state.value == State.Options) finish(Pending.Cancel)
    }

    /** Focus loss (call, OS dialog, backgrounding) discards any in-progress recording. */
    fun discardOnFocusLoss() {
        if (_state.value == State.Idle) return
        if (recorder?.isRecording() == true) finish(Pending.Cancel) else reset()
    }

    private fun mount() {
        _state.value = State.Held
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        RecordingState.active.value = true
        val rec = OpusRecorder(Storage.recordingDir(context))
        recorder = rec
        scope.launch(Dispatchers.IO) {
            val result = rec.record()
            onFinished(result)
        }
        timerJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                _elapsedSec.value = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
                delay(500)
            }
        }
    }

    private fun finish(action: Pending) {
        pending = action
        recorder?.stop() // record() returns and calls onFinished
    }

    private suspend fun onFinished(result: OpusRecorder.Result) {
        timerJob?.cancel()
        when (pending) {
            Pending.Send -> if (result.durationSec >= MIN_SEND_SECONDS) {
                onSend(result.path, result.durationSec)
                _exit.value = Exit.Flash
            } else {
                File(result.path).delete()
                _exit.value = Exit.SlideDown
            }
            else -> {
                File(result.path).delete()
                _exit.value = Exit.SlideDown
            }
        }
        NtsLog.i("recording finished: $pending (${result.durationSec}s)")
        reset()
    }

    private fun reset() {
        _state.value = State.Idle
        _top.value = 1f
        _elapsedSec.value = 0
        recorder = null
        pending = Pending.None
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
        RecordingState.active.value = false
    }
}
