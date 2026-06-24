package com.humanemagica.nothing.terminal.net

import org.json.JSONObject

/**
 * The specs/protocol.md control plane (JSON text frames). Binary frames carry raw
 * terminal bytes and need no parsing.
 */
object Protocol {
    fun resizeFrame(cols: Int, rows: Int): String =
        JSONObject().put("t", "resize").put("cols", cols).put("rows", rows).toString()

    /** (cols, rows) of a `size` control frame, or null for anything else. */
    fun parseSize(text: String): Pair<Int, Int>? = try {
        val o = JSONObject(text)
        if (o.optString("t") == "size") o.getInt("cols") to o.getInt("rows") else null
    } catch (_: Exception) {
        null
    }
}
