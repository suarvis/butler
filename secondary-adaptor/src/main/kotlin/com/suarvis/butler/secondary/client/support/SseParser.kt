package com.suarvis.butler.secondary.client.support

/** One Server-Sent Event: the optional `event:` name and the joined `data:` lines. */
data class SseEvent(val event: String?, val data: String)

/** Minimal Server-Sent Events parser (https://html.spec.whatwg.org/multipage/server-sent-events.html). */
object SseParser {
    fun parse(lines: Iterator<String>, onEvent: (SseEvent) -> Unit) {
        var event: String? = null
        val data = StringBuilder()

        fun dispatch() {
            if (data.isNotEmpty()) onEvent(SseEvent(event, data.toString()))
            event = null
            data.setLength(0)
        }

        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter("data:").removePrefix(" "))
                }
            }
        }
        dispatch()
    }
}
