package com.suarvis.butler.api.cli.prompt

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.BashRisk
import com.suarvis.butler.core.domain.shell.CommandIntent
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

class CliToolApproverTest {
    private class FakeConsole(typed: String, override val interactive: Boolean = true) : CliConsole {
        private val lines = typed.byteInputStream().bufferedReader()
        private val outBuffer = ByteArrayOutputStream()
        private val errBuffer = ByteArrayOutputStream()

        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val out = PrintStream(outBuffer, true, Charsets.UTF_8)
        override val err = PrintStream(errBuffer, true, Charsets.UTF_8)

        override fun readLine(): String? = lines.readLine()

        val printed: String
            get() = outBuffer.toString(Charsets.UTF_8).replace(System.lineSeparator(), "\n")

        val warnings: String
            get() = errBuffer.toString(Charsets.UTF_8)
    }

    private val editInput = """{"path":"src/App.kt","old_string":"val x = 1","new_string":"val x = 2"}"""
    private val editDiff = "--- src/App.kt\n+++ src/App.kt\n-val x = 1\n+val x = 2\n"
    private val escalation = "tool 'edit_file' requires approval to escalate from prompt to workspace-write"

    private fun editRequest(reason: String = escalation, preview: String? = editDiff) =
        ToolApprovalRequest(
            call = ContentBlock.ToolUse(ToolCallId("toolu_edit"), ToolName("edit_file"), JsonText(editInput)),
            invocation = ToolInvocation.EditFile(WorkspacePath("src/App.kt"), "val x = 1", "val x = 2"),
            tier = PermissionTier.WORKSPACE_WRITE,
            mode = PermissionMode.PROMPT,
            reason = reason,
            preview = preview,
        )

    private fun writeRequest(content: String) =
        ToolApprovalRequest(
            call = ContentBlock.ToolUse(ToolCallId("toolu_write"), ToolName("write_file"), JsonText("""{"path":"notes.md","content":"$content"}""")),
            invocation = ToolInvocation.WriteFile(WorkspacePath("notes.md"), content),
            tier = PermissionTier.WORKSPACE_WRITE,
            mode = PermissionMode.PROMPT,
            reason = "tool 'write_file' requires approval to escalate from prompt to workspace-write",
            preview = null,
        )

    private fun bashRequest(command: String, risk: BashRisk?) =
        ToolApprovalRequest(
            call = ContentBlock.ToolUse(ToolCallId("toolu_bash"), ToolName("bash"), JsonText("""{"command":"$command"}""")),
            invocation = ToolInvocation.Bash(command),
            tier = PermissionTier.DANGER_FULL_ACCESS,
            mode = PermissionMode.PROMPT,
            reason = "tool 'bash' requires approval to escalate from prompt to danger-full-access",
            preview = command,
            bashRisk = risk,
        )

    private val traversal =
        BashRisk(
            CommandIntent.WRITE,
            PermissionTier.DANGER_FULL_ACCESS,
            "Command contains directory traversal pattern '../' — verify the target path resolves within the workspace",
        )

    private val destructive =
        BashRisk(
            CommandIntent.DESTRUCTIVE,
            PermissionTier.DANGER_FULL_ACCESS,
            "Destructive command detected: Recursive forced deletion of current directory",
        )

    private fun approver(console: CliConsole, format: OutputFormat = OutputFormat.TEXT) = CliToolApprover(console, format)

    @Test
    fun `the prompt prints Clawcode's block, then the diff, then the question`() {
        val console = FakeConsole("y\n")

        approver(console).decide(editRequest())

        assertEquals(
            "\n" +
                "Permission approval required\n" +
                "  Tool             edit_file\n" +
                "  Current mode     prompt\n" +
                "  Required mode    workspace-write\n" +
                "  Reason           $escalation\n" +
                "  Input            $editInput\n" +
                "--- src/App.kt\n" +
                "+++ src/App.kt\n" +
                "-val x = 1\n" +
                "+val x = 2\n" +
                "Approve this tool call? [y/a/N]: ",
            console.printed,
        )
    }

    @Test
    fun `y or yes allows once and asks again next time`() {
        val console = FakeConsole("y\nYES\n")
        val approver = approver(console)

        assertEquals(ToolApprovalDecision.ALLOW_ONCE, approver.decide(editRequest()))
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, approver.decide(editRequest()))
        assertEquals(2, Regex("Permission approval required").findAll(console.printed).count())
    }

    @Test
    fun `a allows a file write for the session, and the tool is not asked about again`() {
        val console = FakeConsole("a\n")
        val approver = approver(console)

        assertEquals(ToolApprovalDecision.ALLOW_FOR_SESSION, approver.decide(editRequest()))
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, approver.decide(editRequest(preview = "--- src/Other.kt\n")))
        assertEquals(1, Regex("Permission approval required").findAll(console.printed).count())
    }

    @Test
    fun `always works like a, and a grant for one tool does not cover another`() {
        val console = FakeConsole("always\nn\n")
        val approver = approver(console)

        assertEquals(ToolApprovalDecision.ALLOW_FOR_SESSION, approver.decide(editRequest()))
        assertEquals(ToolApprovalDecision.DENY, approver.decide(writeRequest("hello")))
    }

    @Test
    fun `a grant given because the mode asked does not silence an ask rule`() {
        val console = FakeConsole("a\n\n")
        val approver = approver(console)
        val askRule = "tool 'edit_file' requires approval due to ask rule 'edit_file(.github/:*)'"

        assertEquals(ToolApprovalDecision.ALLOW_FOR_SESSION, approver.decide(editRequest()))
        assertEquals(ToolApprovalDecision.DENY, approver.decide(editRequest(reason = askRule)))
        assertTrue(console.printed.contains("  Reason           $askRule"), console.printed)
    }

    @Test
    fun `anything else, an empty answer or the end of input denies`() {
        listOf("no\n", "maybe\n", "\n", "").forEach { typed ->
            assertEquals(ToolApprovalDecision.DENY, approver(FakeConsole(typed)).decide(editRequest()), "typed '$typed'")
        }
    }

    @Test
    fun `a bash command that is not destructive can be allowed for the session, per exact command, with its risk warning`() {
        val console = FakeConsole("a\nn\n")
        val approver = approver(console)

        assertEquals(ToolApprovalDecision.ALLOW_FOR_SESSION, approver.decide(bashRequest("cp ../template.md notes.md", traversal)))
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, approver.decide(bashRequest("cp ../template.md notes.md", traversal)))
        assertEquals(ToolApprovalDecision.DENY, approver.decide(bashRequest("cp ../other.md notes.md", traversal)))
        assertEquals(2, Regex("Permission approval required").findAll(console.printed).count())
        assertTrue(console.printed.contains("\ncp ../template.md notes.md\nwarning: Command contains directory traversal pattern '../'"), console.printed)
        assertTrue(console.printed.contains("Approve this tool call? [y/a/N]: "), console.printed)
    }

    @Test
    fun `destructive bash, or bash without a risk verdict, is never offered a and a typed a denies`() {
        listOf(bashRequest("rm -rf .", destructive), bashRequest("make clean", null)).forEach { request ->
            val console = FakeConsole("a\n")

            assertEquals(ToolApprovalDecision.DENY, approver(console).decide(request))
            assertTrue(console.printed.endsWith("Approve this tool call? [y/N]: "), console.printed)
            assertFalse(console.printed.contains("[y/a/N]"), console.printed)
        }
    }

    @Test
    fun `a destructive command is asked about every time, even after y`() {
        val console = FakeConsole("y\ny\n")
        val approver = approver(console)

        repeat(2) { assertEquals(ToolApprovalDecision.ALLOW_ONCE, approver.decide(bashRequest("rm -rf .", destructive))) }
        assertEquals(2, Regex("Permission approval required").findAll(console.printed).count())
        assertTrue(console.printed.contains("warning: Destructive command detected"), console.printed)
    }

    @Test
    fun `the Input line is cut at 500 characters`() {
        val content = "x".repeat(600)
        val console = FakeConsole("n\n")

        approver(console).decide(writeRequest(content))

        val input = console.printed.lines().single { it.startsWith("  Input            ") }.removePrefix("  Input            ")
        assertEquals("""{"path":"notes.md","content":"${"x".repeat(470)}…""", input)
    }

    @Test
    fun `scripts are never asked and are denied, with one line on stderr`() {
        val console = FakeConsole("y\n", interactive = false)
        val approver = approver(console)

        assertEquals(ToolApprovalDecision.DENY, approver.decide(editRequest()))
        assertEquals("", console.printed)
        assertEquals("denied edit_file: $escalation", console.warnings.trim())
        assertEquals(listOf(editRequest()), approver.denials)
    }

    @Test
    fun `JSON output is never asked and prints nothing, even at a terminal`() {
        val console = FakeConsole("y\n")
        val approver = approver(console, OutputFormat.JSON)

        assertEquals(ToolApprovalDecision.DENY, approver.decide(editRequest()))
        assertEquals("", console.printed)
        assertEquals("", console.warnings)
        assertEquals(listOf(editRequest()), approver.denials)
    }

    @Test
    fun `a request without a preview still shows the full block`() {
        val console = FakeConsole("n\n")

        approver(console).decide(editRequest(preview = null))

        assertTrue(console.printed.endsWith("  Input            $editInput\nApprove this tool call? [y/a/N]: "), console.printed)
    }
}
