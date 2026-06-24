package com.humanemagica.nothing.terminal.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The REST control plane: scripts, the session ring, and the voice propose/send
 * flow. Shapes mirror the nothing-serious backend brief (and the Go stand-in).
 */
class ControlApi(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder().url(base + path).apply {
        if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
    }

    suspend fun scripts(): List<Script> = withContext(Dispatchers.IO) {
        http.newCall(req("/scripts").get().build()).execute().use { r ->
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { val o = arr.getJSONObject(it); Script(o.getString("id"), o.getString("label")) }
        }
    }

    suspend fun sessions(): List<Session> = withContext(Dispatchers.IO) {
        http.newCall(req("/sessions").get().build()).execute().use { r ->
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { sessionFrom(arr.getJSONObject(it)) }
        }
    }

    suspend fun open(scriptId: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("script_id", scriptId).toString().toRequestBody(jsonType)
        http.newCall(req("/sessions").post(body).build()).execute().use { r ->
            sessionFrom(JSONObject(r.body!!.string()))
        }
    }

    suspend fun close(id: String) = withContext(Dispatchers.IO) {
        http.newCall(req("/sessions/$id").delete().build()).execute().close()
    }

    /** Propose: upload audio (ignored by the mock), get back the proposed action. */
    suspend fun voice(id: String, audio: ByteArray?): Proposal = withContext(Dispatchers.IO) {
        val body = (audio ?: ByteArray(0)).toRequestBody("application/octet-stream".toMediaType())
        http.newCall(req("/sessions/$id/voice").post(body).build()).execute().use { r ->
            val o = JSONObject(r.body!!.string())
            Proposal(o.getString("transcript"), o.optJSONObject("action")?.let { actionFrom(it) })
        }
    }

    suspend fun send(id: String, action: Action) = withContext(Dispatchers.IO) {
        val a = JSONObject().apply {
            when (action) {
                is Action.Text -> { put("kind", "text"); put("text", action.text) }
                is Action.Signal -> { put("kind", "signal"); put("signal", action.signal) }
            }
        }
        val body = JSONObject().put("action", a).toString().toRequestBody(jsonType)
        http.newCall(req("/sessions/$id/send").post(body).build()).execute().close()
    }

    private fun sessionFrom(o: JSONObject) = Session(
        o.getString("id"), o.optString("script_id"), o.optString("label"), o.optInt("cols"), o.optInt("rows"),
    )

    private fun actionFrom(o: JSONObject): Action =
        if (o.optString("kind") == "signal") Action.Signal(o.optString("signal", "INT"))
        else Action.Text(o.optString("text"))
}
