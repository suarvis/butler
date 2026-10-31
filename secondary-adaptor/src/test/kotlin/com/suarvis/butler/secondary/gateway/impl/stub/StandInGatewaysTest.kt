package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Until the real tool lanes land, every stand-in must say plainly that its tool is missing. */
class StandInGatewaysTest {
    private val workspace = SessionFixtures.workspace

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
