package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/** The tool doubles other tests lean on, plus the defaults they hand out. */
class ToolDoublesTest {
    private val workspace = SessionFixtures.workspace
    private val readme = ToolInvocation.ReadFile(WorkspacePath("README.md"))
    private val edit = ToolInvocation.EditFile(WorkspacePath("README.md"), "old", "new")

    @Test
    fun `fake tool input gateway decodes with the given function`() {
        val gateway = FakeToolInputGateway { name, input -> ToolInvocation.External(name, input) }

        assertEquals(
            ToolInvocation.External(ToolName("mcp__docs__search"), JsonText("{}")),
            gateway.decode(workspace, ToolName("mcp__docs__search"), JsonText("{}")),
        )
    }

    @Test
    fun `recording workspace gateway answers in order and records each invocation`() {
        val gateway = RecordingWorkspaceGateway(ToolOutcome("first"), ToolOutcome("second", isError = true))

        assertEquals(ToolOutcome("first"), gateway.run(workspace, readme))
        assertEquals(ToolOutcome("second", isError = true), gateway.run(workspace, edit))
        assertEquals(listOf(readme, edit), gateway.invocations)
    }

    @Test
    fun `recording workspace gateway fails once its script runs out`() {
        val gateway = RecordingWorkspaceGateway(ToolOutcome("only"))
        gateway.run(workspace, readme)

        val error = assertThrows<IllegalStateException> { gateway.run(workspace, readme) }

        assertEquals("no scripted outcome left", error.message)
    }

    @Test
    fun `recording workspace gateway previews only what it was given`() {
        val gateway = RecordingWorkspaceGateway(previews = mapOf(edit to "-old\n+new"))

        assertEquals("-old\n+new", gateway.preview(workspace, edit))
        assertNull(gateway.preview(workspace, readme))
        assertTrue(gateway.invocations.isEmpty(), "previewing does not run the tool")
    }

    @Test
    fun `recording command gateway answers in order and records commands with their sandbox`() {
        val gateway = RecordingCommandGateway(ToolOutcome("main.kt"), ToolOutcome("deleted"))
        val sandbox = SandboxPolicy.forWorkspace(workspace)
        val open = SandboxPolicy(listOf(workspace.path), network = true)
        val list = ToolInvocation.Bash("ls")
        val remove = ToolInvocation.Bash("rm main.kt")

        assertEquals(ToolOutcome("main.kt"), gateway.run(workspace, list, sandbox, TurnCancellation.NEVER))
        assertEquals(ToolOutcome("deleted"), gateway.run(workspace, remove, open, TurnCancellation.NEVER))
        assertEquals(listOf(list, remove), gateway.commands)
        assertEquals(listOf(sandbox, open), gateway.sandboxes)
        assertThrows<IllegalStateException> { gateway.run(workspace, list, sandbox, TurnCancellation.NEVER) }
    }

    @Test
    fun `fake MCP gateway offers its tools, records calls and refuses unknown tools`() {
        val search = ToolName("mcp__docs__search")
        val gateway =
            FakeMcpGateway(
                tools = listOf(BuiltInTools.READ_FILE),
                outcomes = mapOf(search to ToolOutcome("3 hits")),
            )

        assertEquals(listOf(BuiltInTools.READ_FILE), gateway.tools())
        assertEquals(ToolOutcome("3 hits"), gateway.call(search, JsonText("""{"q":"budget"}""")))
        val unknown = gateway.call(ToolName("mcp__docs__delete"), JsonText("{}"))
        assertTrue(unknown.isError)
        assertTrue(unknown.output.contains("mcp__docs__delete"), unknown.output)
        assertEquals(
            listOf(
                FakeMcpGateway.Call(search, JsonText("""{"q":"budget"}""")),
                FakeMcpGateway.Call(ToolName("mcp__docs__delete"), JsonText("{}")),
            ),
            gateway.calls,
        )
    }

    @Test
    fun `fixed project context gateway returns its context and records the workspace`() {
        val gateway = FixedProjectContextGateway()

        assertEquals(ProjectContext.EMPTY, gateway.load(workspace))
        assertEquals(listOf(workspace), gateway.workspaces)
    }

    @Test
    fun `cancel after stays clear for the given number of checks and is cancelled from then on`() {
        val cancellation = CancelAfter(2)

        assertEquals(listOf(false, false, true, true), List(4) { cancellation.isCancelled() })
        assertTrue(CancelAfter(0).isCancelled())
        assertFalse(TurnCancellation.NEVER.isCancelled())
    }

    @Test
    fun `the default sandbox writes only inside the workspace and has no network`() {
        assertEquals(SandboxPolicy(listOf(workspace.path), network = false), SandboxPolicy.forWorkspace(workspace))
    }

    @Test
    fun `sandbox writable roots must be absolute`() {
        assertThrows<DomainValidationException> { SandboxPolicy(listOf(Path.of("build")), network = false) }
    }

    @Test
    fun `the empty project context has no instructions and no git`() {
        assertTrue(ProjectContext.EMPTY.instructions.isEmpty())
        assertNull(ProjectContext.EMPTY.git)
    }
}
