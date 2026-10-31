package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import java.io.PrintStream

/**
 * Writes the model's text to the terminal as it arrives, with each tool call and its result in
 * Clawcode's layout ([ToolCallRenderer]). Thinking is not shown. Markdown rendering (Clawcode's
 * syntax-highlighted output) comes later; this prints raw Markdown. Used in text mode only: JSON
 * and `--compact` output never see these lines.
 */
class StreamingTurnObserver(
    private val out: PrintStream,
    style: TerminalStyle = TerminalStyle.PLAIN,
) : TurnObserver {
    private val tools = ToolCallRenderer(style)

    /** The last two characters written, to place line breaks without doubling them. */
    private var tail = ""

    /** Set after a tool result, so the model's next text starts after a blank line. */
    private var separateNextText = false

    override fun onEvent(event: TurnEvent) {
        when (event) {
            is TurnEvent.TextDelta -> {
                if (event.text.isEmpty()) return
                if (separateNextText) separate()
                separateNextText = false
                write(event.text)
                out.flush()
            }
            is TurnEvent.ToolStarted -> {
                // Clawcode prints a newline before the frame; here a blank line parts it from earlier output.
                separate()
                writeLines(tools.started(event.call))
            }
            is TurnEvent.ToolFinished -> {
                endLine()
                writeLines(tools.finished(event.call, event.result))
                separateNextText = true
            }
            is TurnEvent.Completed -> {
                endLine()
                out.flush()
            }
            is TurnEvent.Started, is TurnEvent.ThinkingDelta -> Unit
        }
    }

    /** Ends a half-printed line of model text. */
    private fun endLine() {
        if (tail.isNotEmpty() && !tail.endsWith('\n')) newline()
    }

    /** Leaves one blank line after whatever was printed before; nothing at the very start. */
    private fun separate() {
        endLine()
        if (tail.isNotEmpty() && !tail.endsWith("\n\n")) newline()
    }

    private fun writeLines(lines: List<String>) {
        lines.forEach { line ->
            write(line)
            newline()
        }
        out.flush()
    }

    private fun write(text: String) {
        out.print(text)
        tail = (tail + text).takeLast(2)
    }

    private fun newline() {
        out.println()
        tail = (tail + "\n").takeLast(2)
    }
}
