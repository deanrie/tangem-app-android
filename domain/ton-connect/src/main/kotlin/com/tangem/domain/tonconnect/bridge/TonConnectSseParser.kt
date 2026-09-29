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
    private var dataByteCount = 0
    private var overflowed = false

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
            "data" -> {
                dataByteCount += value.length + 1
                if (dataByteCount > MAX_EVENT_DATA_BYTE_COUNT) {
                    overflowed = true
                    dataLines.clear()
                } else if (!overflowed) {
                    dataLines += value
                }
            }
        }
        return null
    }

    private fun flush(): TonConnectSseEvent? {
        // An oversized event is dropped whole rather than delivered truncated.
        val result = if (overflowed || dataLines.isEmpty()) null else TonConnectSseEvent(id, event, dataLines.joinToString("\n"))
        event = null
        dataLines.clear()
        dataByteCount = 0
        overflowed = false
        return result
    }

    companion object {
        /**
         * Upper bound on the accumulated `data:` of one event. A bridge (or anyone able to inject into the stream)
         * must not be able to grow the wallet's memory without ever sending the terminating blank line.
         */
        const val MAX_EVENT_DATA_BYTE_COUNT: Int = 1024 * 1024
    }
}
