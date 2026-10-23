package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Until the real tool lanes land, every stand-in must say plainly that its tool is missing. */
class StandInGatewaysTest {
    private val workspace = SessionFixtures.workspace

    @Test
    fun `tool input is refused, naming the tool`() {
        val error =
            assertThrows<InvalidToolInputException> {
                UnavailableToolInputGatewayImpl().decode(workspace, ToolName("read_file"), JsonText("""{"path":"README.md"}"""))
            }

        assertEquals(ToolName("read_file"), error.tool)
        assertEquals("Invalid input for read_file: tools are not available in this build yet", error.message)
    }

    @Test
    fun `file tools answer with an error naming the built-in tool`() {
        val gateway = UnavailableWorkspaceGatewayImpl()
        val path = WorkspacePath("README.md")
        val invocations =
            mapOf(
                ToolInvocation.ReadFile(path) to "read_file",
                ToolInvocation.GlobSearch("**/*.kt") to "glob_search",
                ToolInvocation.GrepSearch("TODO") to "grep_search",
                ToolInvocation.WriteFile(path, "hello") to "write_file",
                ToolInvocation.EditFile(path, "hello", "goodbye") to "edit_file",
                ToolInvocation.Bash("ls") to "bash",
            )

        invocations.forEach { (invocation, name) ->
            assertEquals(ToolOutcome("$name is not available in this build yet", isError = true), gateway.run(workspace, invocation))
        }
    }

    @Test
    fun `external tools are named by the tool they call`() {
        val outcome = UnavailableWorkspaceGatewayImpl().run(workspace, ToolInvocation.External(ToolName("mcp__docs__search"), JsonText("{}")))

        assertEquals(ToolOutcome("mcp__docs__search is not available in this build yet", isError = true), outcome)
    }

    @Test
    fun `file tools offer no preview`() {
        assertNull(UnavailableWorkspaceGatewayImpl().preview(workspace, ToolInvocation.WriteFile(WorkspacePath("a.txt"), "a")))
    }

    @Test
    fun `bash answers with an error`() {
        val outcome =
            UnavailableCommandGatewayImpl().run(
                workspace,
                ToolInvocation.Bash("ls"),
                SandboxPolicy.forWorkspace(workspace),
                TurnCancellation.NEVER,
            )

        assertEquals(ToolOutcome("bash is not available in this build yet", isError = true), outcome)
    }

    @Test
    fun `MCP offers no tools and refuses calls, naming the tool`() {
        val gateway = NoMcpGatewayImpl()

        val outcome = gateway.call(ToolName("mcp__docs__search"), JsonText("{}"))

        assertTrue(gateway.tools().isEmpty())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("mcp__docs__search"), outcome.output)
    }

    @Test
    fun `project context is empty`() {
        assertEquals(ProjectContext.EMPTY, NoProjectContextGatewayImpl().load(workspace))
    }
}
