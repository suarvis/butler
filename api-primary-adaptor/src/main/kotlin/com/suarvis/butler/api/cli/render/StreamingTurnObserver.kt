package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import java.io.PrintStream

/**
 * Writes the model's text to the terminal as it arrives. Thinking is not shown. Markdown
 * rendering (Clawcode's syntax-highlighted output) comes later; this prints raw Markdown.
 */
class StreamingTurnObserver(private val out: PrintStream) : TurnObserver {
    private var lastChar: Char? = null

    override fun onEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.TextDelta -> {
                if (event.text.isEmpty()) return
                out.print(event.text)
                out.flush()
                lastChar = event.text.last()
            }
            is TurnEvent.Completed -> {
                if (lastChar != null && lastChar != '\n') out.println()
                out.flush()
            }
            is TurnEvent.Started, is TurnEvent.ThinkingDelta -> Unit
        }
    }
}
