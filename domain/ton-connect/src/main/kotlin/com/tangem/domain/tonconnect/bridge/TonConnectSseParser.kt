package com.tangem.domain.tonconnect.bridge

/** One `text/event-stream` event. */
data class TonConnectSseEvent(
    val id: String?,
    val event: String?,
    val data: String,
) {
    /** Bridge keep-alives: `event: heartbeat` (legacy) or `event: message` + `data: heartbeat`. */
    val isHeartbeat: Boolean get() = event == "heartbeat" || data == "heartbeat"
}

/**
 * Incremental line-oriented Server-Sent-Events parser (WHATWG EventSource algorithm, subset).
 *
 * Feed it lines without their terminator; an event is emitted on every empty line. Comment lines (`:`) and unknown
 * fields are ignored, `data:` lines are joined with `\n`. `id` is sticky across events, matching EventSource.
 */
class TonConnectSseParser {

    private var id: String? = null
    private var event: String? = null
    private val dataLines = mutableListOf<String>()

    /** Returns a complete event when [rawLine] terminates one, `null` otherwise. */
    fun feed(rawLine: String): TonConnectSseEvent? {
        val line = rawLine.removeSuffix("\r")

        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field: String
        val value: String
        if (colon >= 0) {
            field = line.substring(0, colon)
            value = line.substring(colon + 1).removePrefix(" ")
        } else {
            field = line
            value = ""
        }

        when (field) {
            "id" -> if (!value.contains('\u0000')) id = value // per spec, an id containing NUL is ignored
            "event" -> event = value
            "data" -> dataLines += value
        }
        return null
    }

    private fun flush(): TonConnectSseEvent? {
        val result = if (dataLines.isEmpty()) null else TonConnectSseEvent(id, event, dataLines.joinToString("\n"))
        event = null
        dataLines.clear()
        return result
    }
}
