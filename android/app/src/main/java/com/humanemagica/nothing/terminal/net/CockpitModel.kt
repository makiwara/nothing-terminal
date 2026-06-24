package com.humanemagica.nothing.terminal.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Voice proposal under review (the Cancel / Adjust / Send overlay). */
data class Review(val sessionId: String, val transcript: String, val action: Action?)

/**
 * The control-plane singleton: holds the session ring, the script catalog, and
 * the voice review state, talking to the backend over [ControlApi]. The data
 * plane (per-session byte streams) is owned by each terminal view's own
 * [SessionConnection]; this model never touches bytes.
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

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state
    private val _ring = MutableStateFlow<List<Session>>(emptyList())
    val ring: StateFlow<List<Session>> = _ring
    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts
    private val _review = MutableStateFlow<Review?>(null)
    val review: StateFlow<Review?> = _review

    fun start(baseUrl: String, token: String) {
        this.token = token
        this.wsBase = baseUrl.replaceFirst("http", "ws").trimEnd('/')
        api = ControlApi(baseUrl, token)
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

    /** Propose step: upload the recorded audio, surface the proposal for review. */
    fun propose(sessionId: String, audio: ByteArray?) = scope.launch {
        try {
            val p = api.voice(sessionId, audio)
            _review.value = Review(sessionId, p.transcript, p.action)
        } catch (e: Exception) {
            _review.value = Review(sessionId, "voice failed: ${e.message}", null)
        }
    }

    fun sendReviewed() {
        val r = _review.value ?: return
        _review.value = null
        val a = r.action ?: return
        scope.launch { try { api.send(r.sessionId, a) } catch (_: Exception) {} }
    }

    fun cancelReview() {
        _review.value = null
    }
}
