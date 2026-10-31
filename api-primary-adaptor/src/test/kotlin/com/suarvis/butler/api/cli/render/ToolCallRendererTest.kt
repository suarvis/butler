package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Clawcode's tool-call layout, words and display caps, without emoji, checked line by line. */
class ToolCallRendererTest {
    private val renderer = ToolCallRenderer()

    private fun call(name: String, input: String) = ContentBlock.ToolUse(ToolCallId("toolu_1"), ToolName(name), JsonText(input))

    private fun succeeded(call: ContentBlock.ToolUse, output: String, using: ToolCallRenderer = renderer) =
        using.finished(call, ContentBlock.ToolResult(call.id, call.name, output, isError = false))

    private fun failed(call: ContentBlock.ToolUse, output: String) =
        renderer.finished(call, ContentBlock.ToolResult(call.id, call.name, output, isError = true))

    private fun numbered(prefix: String, count: Int) = (0 until count).joinToString("\n") { "$prefix %03d".format(it) }

    @Test
    fun `read_file starts with Clawcode's frame and no emoji`() {
        assertEquals(
            listOf("╭─ read_file ─╮", "│ Reading src/main.rs…", "╰" + "─".repeat(17) + "╯"),
            renderer.started(call("read_file", """{"path":"src/main.rs"}""")),
        )
    }

    @Test
    fun `write_file start counts the lines it will write`() {
        val lines = renderer.started(call("write_file", """{"path":"notes.md","content":"a\nb\nc\n"}"""))

        assertEquals("│ Writing notes.md (3 lines)", lines[1])
    }

    @Test
    fun `edit_file start previews the first visible old and new line`() {
        val lines = renderer.started(call("edit_file", """{"path":"App.kt","old_string":"\n  val x = 1\nmore","new_string":"  val x = 2"}"""))

        assertEquals(listOf("╭─ edit_file ─╮", "│ Editing App.kt", "-   val x = 1", "+   val x = 2"), lines.dropLast(1))
    }

    @Test
    fun `glob and grep starts show the pattern and the folder searched`() {
        assertEquals(listOf("│ Glob **/*.kt", "in src"), renderer.started(call("glob_search", """{"pattern":"**/*.kt","path":"src"}""")).subList(1, 3))
        assertEquals(listOf("│ Grep TODO", "in ."), renderer.started(call("grep_search", """{"pattern":"TODO"}""")).subList(1, 3))
    }

    @Test
    fun `bash start shows the command`() {
        assertEquals("│  $ ls -la ", renderer.started(call("bash", """{"command":"ls -la"}"""))[1])
    }

    @Test
    fun `a tool Butler does not know shows its input as compact JSON, cut at 96 characters`() {
        val short = renderer.started(call("mcp__docs__search", """{ "query" : "kotlin" }"""))
        assertEquals(listOf("╭─ mcp__docs__search ─╮", """│ {"query":"kotlin"}""", "╰" + "─".repeat(25) + "╯"), short)

        val long = renderer.started(call("mcp__docs__search", """{"query":"${"q".repeat(200)}"}"""))[1]
        assertEquals("│ ".length + 96 + 1, long.length)
        assertTrue(long.endsWith("…"), long)
    }

    @Test
    fun `read_file result names the lines read and shows them`() {
        val output = """{"type":"text","file":{"filePath":"/w/src/main.rs","content":"fn main() {}\n","numLines":1,"startLine":1,"totalLines":1}}"""

        assertEquals(listOf("✓ Read /w/src/main.rs (lines 1-1 of 1)", "fn main() {}"), succeeded(call("read_file", """{"path":"src/main.rs"}"""), output))
    }

    @Test
    fun `a 100-line read shows 80 lines and the truncation notice`() {
        val output =
            buildJsonObject {
                put("type", "text")
                putJsonObject("file") {
                    put("filePath", "/w/big.txt")
                    put("content", numbered("line", 100))
                    put("numLines", 100)
                    put("startLine", 1)
                    put("totalLines", 100)
                }
            }.toString()

        val lines = succeeded(call("read_file", """{"path":"big.txt"}"""), output)

        assertEquals("✓ Read /w/big.txt (lines 1-100 of 100)", lines.first())
        assertEquals(1 + 80 + 1, lines.size)
        assertEquals("line 079", lines[80])
        assertEquals("… output truncated for display; full result preserved in session.", lines.last())
    }

    @Test
    fun `a read longer than 6000 characters is cut there`() {
        val output =
            buildJsonObject {
                putJsonObject("file") {
                    put("filePath", "/w/wide.txt")
                    put("content", "x".repeat(7_000))
                    put("numLines", 1)
                    put("startLine", 1)
                    put("totalLines", 1)
                }
            }.toString()

        val lines = succeeded(call("read_file", """{"path":"wide.txt"}"""), output)

        assertEquals(listOf("x".repeat(6_000), "… output truncated for display; full result preserved in session."), lines.drop(1))
    }

    @Test
    fun `write_file result says whether it wrote or updated the file`() {
        val created = succeeded(call("write_file", """{"path":"notes.md","content":"a\nb\n"}"""), """{"type":"create","filePath":"/w/notes.md","content":"a\nb\n"}""")
        assertEquals(listOf("✓ Wrote /w/notes.md (2 lines)"), created)

        // Without the file echoed back in the envelope, the line count comes from the call.
        val updated = succeeded(call("write_file", """{"path":"notes.md","content":"x\ny\nz"}"""), """{"type":"update","filePath":"/w/notes.md"}""")
        assertEquals(listOf("✓ Updated /w/notes.md (3 lines)"), updated)
    }

    @Test
    fun `edit_file result shows two hunks of at most six lines`() {
        val output =
            buildJsonObject {
                put("filePath", "/w/App.kt")
                put("replaceAll", true)
                putJsonArray("structuredPatch") {
                    add(buildJsonObject { putJsonArray("lines") { listOf(" a", " b", "-c", "+C", " d", " e", " f", " g").forEach { add(it) } } })
                    add(buildJsonObject { putJsonArray("lines") { listOf("-x", "+X").forEach { add(it) } } })
                    add(buildJsonObject { putJsonArray("lines") { listOf("-third", "+THIRD").forEach { add(it) } } })
                }
            }.toString()

        val lines = succeeded(call("edit_file", """{"path":"App.kt","old_string":"c","new_string":"C"}"""), output)

        assertEquals(listOf("✓ Edited /w/App.kt (replace all)", " a", " b", "-c", "+C", " d", " e", "-x", "+X"), lines)
    }

    @Test
    fun `edit_file result without hunks previews the old and new text`() {
        val lines = succeeded(call("edit_file", """{"path":"App.kt","old_string":"old","new_string":"new"}"""), """{"filePath":"/w/App.kt","oldString":"old","newString":"new"}""")

        assertEquals(listOf("✓ Edited /w/App.kt", "- old", "+ new"), lines)
    }

    @Test
    fun `glob_search result counts the matches and lists the first eight`() {
        val output =
            buildJsonObject {
                put("durationMs", 3)
                put("numFiles", 10)
                putJsonArray("filenames") { (0 until 10).forEach { add("/w/f$it.kt") } }
                put("truncated", false)
            }.toString()

        val lines = succeeded(call("glob_search", """{"pattern":"*.kt"}"""), output)

        assertEquals(listOf("✓ glob_search matched 10 files") + (0 until 8).map { "/w/f$it.kt" }, lines)
    }

    @Test
    fun `grep_search result shows matching lines, or the files when there are none`() {
        val content = succeeded(call("grep_search", """{"pattern":"x"}"""), """{"mode":"content","numFiles":2,"filenames":["a","b"],"content":"a:1:x\nb:2:x","numLines":2,"numMatches":2}""")
        assertEquals(listOf("✓ grep_search 2 matches across 2 files", "a:1:x", "b:2:x"), content)

        val files = succeeded(call("grep_search", """{"pattern":"x"}"""), """{"mode":"files_with_matches","numFiles":2,"filenames":["a","b"],"numMatches":0}""")
        assertEquals(listOf("✓ grep_search 0 matches across 2 files", "a", "b"), files)
    }

    @Test
    fun `bash result shows its status, then stdout and stderr`() {
        val lines = succeeded(call("bash", """{"command":"make"}"""), """{"stdout":"built\n","stderr":"warning","returnCodeInterpretation":"exit_code:0"}""")

        assertEquals(listOf("✓ bash exit_code:0", "", "built", "", "warning"), lines)
    }

    @Test
    fun `an error shows its first 160 characters`() {
        val lines = failed(call("read_file", """{"path":"x"}"""), "  " + "e".repeat(200) + "\n")

        assertEquals(listOf("✗ read_file", "e".repeat(160) + "…"), lines)
        assertEquals(listOf("✗ read_file", "File not found"), failed(call("read_file", """{"path":"x"}"""), "File not found"))
        assertEquals(listOf("✗ bash"), failed(call("bash", """{"command":"x"}"""), "   "))
    }

    @Test
    fun `output that is not JSON is shown as text`() {
        assertEquals(listOf("✓ read_file", "plain text", "second line"), succeeded(call("read_file", """{"path":"x"}"""), "plain text\nsecond line"))
        assertEquals(listOf("✓ mcp__x__y: done"), succeeded(call("mcp__x__y", "{}"), "done"))
        assertEquals(listOf("✓ mcp__x__y:   two words"), succeeded(call("mcp__x__y", "{}"), "  two words"))
    }

    @Test
    fun `a JSON result from an unknown tool is pretty-printed and capped at 60 lines`() {
        assertEquals(listOf("✓ mcp__x__y", "{", "  \"ok\": true", "}"), succeeded(call("mcp__x__y", "{}"), """{"ok":true}"""))

        val lines = succeeded(call("mcp__x__y", "{}"), numbered("raw", 120))
        assertEquals(1 + 60 + 1, lines.size)
        assertEquals("raw 059", lines[60])
        assertTrue(lines.last().contains("full result preserved in session"))
    }

    @Test
    fun `without colour no line carries an escape code`() {
        val read = call("read_file", """{"path":"a"}""")
        val edit = call("edit_file", """{"path":"a","old_string":"x","new_string":"y"}""")
        val lines =
            renderer.started(read) + renderer.started(edit) + renderer.started(call("bash", """{"command":"ls"}""")) +
                succeeded(read, """{"file":{"filePath":"a","content":"x","numLines":1,"startLine":1,"totalLines":1}}""") +
                failed(edit, "nope")

        assertFalse(lines.any { it.contains('\u001b') }, lines.joinToString("\n"))
    }

    @Test
    fun `with colour the name, marks and frame are styled as in Clawcode`() {
        val colored = ToolCallRenderer(TerminalStyle.COLOR)
        val read = call("read_file", """{"path":"a"}""")

        val start = colored.started(read)
        assertEquals("\u001b[38;5;245m╭─ \u001b[0m\u001b[1;36mread_file\u001b[0m\u001b[38;5;245m ─╮\u001b[0m", start.first())
        val done = succeeded(read, """{"file":{"filePath":"a","content":"x","numLines":1,"startLine":1,"totalLines":1}}""", colored)
        assertTrue(done.first().startsWith("\u001b[1;32m✓\u001b[0m "), done.first())
        assertEquals("x", done[1])
    }
}
