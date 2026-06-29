package com.humanemagica.nothing.terminal.net

import android.content.Context
import com.humanemagica.nothing.terminal.voice.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Voice proposal under review (the Cancel / Adjust / Confirm overlay). */
data class Review(val sessionId: String, val transcript: String, val action: Action?)

/**
 * The control-plane singleton: holds the session ring, the script catalog, the recorder, and
 * the voice review state, talking to the backend over [ControlApi]. The data plane (per-session
 * byte streams) is owned by each terminal view's own [SessionConnection]; this model never
 * touches bytes.
 */
object CockpitModel {

    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var api: ControlApi
    private var wsBase: String = ""
    private var token: String = ""

    /** The recorder targets whichever terminal is currently visible in the ring. */
    var activeSessionId: String? = null

    /** The reused capture engine (vendored from nothing-to-say). On Send it hands us the OGG. */
    lateinit var recorder: RecordingController
        private set

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state
    private val _ring = MutableStateFlow<List<Session>>(emptyList())
    val ring: StateFlow<List<Session>> = _ring
    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts
    private val _review = MutableStateFlow<Review?>(null)
    val review: StateFlow<Review?> = _review
    private val _transcribing = MutableStateFlow(false)
    val transcribing: StateFlow<Boolean> = _transcribing

    /** Prior transcript carried into the next propose when the operator chose Adjust (refine).
     *  Set by [adjust], consumed once by the next [onCaptured]. */
    private var pendingContext: String? = null

    fun start(context: Context, baseUrl: String, token: String) {
        this.token = token
        this.wsBase = baseUrl.replaceFirst("http", "ws").trimEnd('/')
        api = ControlApi(baseUrl, token)
        recorder = RecordingController(context.applicationContext, scope, ::onCaptured).apply {
            mountAt = 0.33f
            unlockAt = 0.33f
        }
        refresh()
    }

    fun wsUrl(sessionId: String) = "$wsBase/sessions/$sessionId/attach"
    fun token() = token

    fun refresh() {
        if (!::api.isInitialized) return
        scope.launch {
            try {
                _scripts.value = api.scripts()
                _ring.value = api.sessions()
                _state.value = State.Ready
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "unreachable")
            }
        }
    }

    fun open(scriptId: String) = scope.launch {
        try {
            api.open(scriptId)
            _ring.value = api.sessions()
        } catch (_: Exception) {
        }
    }

    fun close(sessionId: String) = scope.launch {
        try {
            api.close(sessionId)
            _ring.value = api.sessions()
        } catch (_: Exception) {
        }
    }

    /** Recorder sink: upload the captured OGG to propose (side-effect-free), surface the proposal
     *  for review, then drop the file. Runs on the recorder's IO coroutine. */
    private suspend fun onCaptured(path: String, durationSec: Int) {
        val sid = activeSessionId ?: run { File(path).delete(); return }
        val context = pendingContext
        pendingContext = null
        _transcribing.value = true
        try {
            val audio = withContext(Dispatchers.IO) { File(path).readBytes() }
            val p = api.voice(sid, audio, context)
            _review.value = Review(sid, p.transcript, p.action)
        } catch (e: Exception) {
            _review.value = Review(sid, "voice failed: ${e.message}", null)
        } finally {
            _transcribing.value = false
            withContext(Dispatchers.IO) { File(path).delete() }
        }
    }

    /** Confirm: inject the reviewed command (the possibly-edited text, or the proposed signal). */
    fun confirm(editedText: String) {
        val r = _review.value ?: return
        _review.value = null
        pendingContext = null
        val action = when (val a = r.action) {
            is Action.Signal -> a
            else -> Action.Text(editedText)
        }
        scope.launch { try { api.send(r.sessionId, action) } catch (_: Exception) {} }
    }

    fun cancelReview() {
        _review.value = null
        pendingContext = null
    }

    /** Adjust: re-record to refine. Carry the reviewed transcript as context for the next propose,
     *  dismiss the review, and bring the recorder back up hands-free per specs/ui/review.md. We drive
     *  the vendored controller through its public gesture API (arm → mount → lock) so it re-enters
     *  RECORDING without a finger; Send/Cancel on the locked surface then drive it. */
    fun adjust(priorTranscript: String) {
        pendingContext = priorTranscript
        _review.value = null
        recorder.arm()
        recorder.updateDrag(0f) // Arming → mount: capture starts (Held)
        recorder.updateDrag(0f) // Held → Locked: hands-free, button-driven
    }
}
