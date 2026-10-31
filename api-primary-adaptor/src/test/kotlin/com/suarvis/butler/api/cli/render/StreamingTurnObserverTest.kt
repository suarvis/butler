package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.turn.TurnEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Model text and tool blocks interleave with one line break where a line ends and one blank line between blocks. */
class StreamingTurnObserverTest {
    private val buffer = ByteArrayOutputStream()
    private val observer = StreamingTurnObserver(PrintStream(buffer, true, Charsets.UTF_8))

    private fun printed(): String = buffer.toString(Charsets.UTF_8).replace(System.lineSeparator(), "\n")

    private fun readCall(id: String, path: String) = ContentBlock.ToolUse(ToolCallId(id), ToolName("read_file"), JsonText("""{"path":"$path"}"""))

    private fun readResult(call: ContentBlock.ToolUse, path: String, content: String) =
        ContentBlock.ToolResult(
            call.id,
            call.name,
            """{"file":{"filePath":"$path","content":"$content","numLines":1,"startLine":1,"totalLines":1}}""",
            isError = false,
        )

    @Test
    fun `text, a tool call and more text each start on their own line`() {
        val call = readCall("toolu_1", "README.md")

        observer.onEvent(TurnEvent.TextDelta("Let me look"))
        observer.onEvent(TurnEvent.TextDelta("."))
        observer.onEvent(TurnEvent.ToolStarted(call))
        observer.onEvent(TurnEvent.ToolFinished(call, readResult(call, "README.md", "# Demo")))
        observer.onEvent(TurnEvent.TextDelta("It is a heading."))

        assertEquals(
            """
            Let me look.

            ╭─ read_file ─╮
            │ Reading README.md…
            ╰─────────────────╯
            ✓ Read README.md (lines 1-1 of 1)
            # Demo

            It is a heading.
            """.trimIndent(),
            printed(),
        )
    }

    @Test
    fun `text that already ends in a blank line gets no second one`() {
        val call = readCall("toolu_1", "a.txt")

        observer.onEvent(TurnEvent.TextDelta("Checking.\n\n"))
        observer.onEvent(TurnEvent.ToolStarted(call))

        assertEquals("Checking.\n\n╭─ read_file ─╮\n", printed().substringBefore("│"))
    }

    @Test
    fun `calls in a row are parted by a blank line, with nothing before the first`() {
        val first = readCall("toolu_1", "a.txt")
        val second = readCall("toolu_2", "b.txt")

        observer.onEvent(TurnEvent.ToolStarted(first))
        observer.onEvent(TurnEvent.ToolFinished(first, readResult(first, "a.txt", "A")))
        observer.onEvent(TurnEvent.ToolStarted(second))
        observer.onEvent(TurnEvent.ToolFinished(second, readResult(second, "b.txt", "B")))

        assertEquals(
            """
            ╭─ read_file ─╮
            │ Reading a.txt…
            ╰─────────────────╯
            ✓ Read a.txt (lines 1-1 of 1)
            A

            ╭─ read_file ─╮
            │ Reading b.txt…
            ╰─────────────────╯
            ✓ Read b.txt (lines 1-1 of 1)
            B

            """.trimIndent(),
            printed(),
        )
    }
}
