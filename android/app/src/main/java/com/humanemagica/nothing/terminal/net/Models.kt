package com.humanemagica.nothing.terminal.net

/** A catalog entry the "open new" menu lists; `command` is the selector's hint line. */
data class Script(val id: String, val label: String, val command: String = "")

/** One open terminal in the ring. Exit fields are populated only once `state == "exited"`. */
data class Session(
    val id: String,
    val scriptId: String,
    val label: String,
    val cols: Int,
    val rows: Int,
    val state: String = "running",
    val startedAt: String = "",
    val exitCode: Int? = null,
    val exitReason: String? = null,
)

/** A voice action proposed by the backend, confirmed on Send. */
sealed interface Action {
    data class Text(val text: String) : Action
    data class Signal(val signal: String) : Action
}

/** The propose result: the cleaned transcript plus the action it maps to. */
data class Proposal(val transcript: String, val action: Action?)
