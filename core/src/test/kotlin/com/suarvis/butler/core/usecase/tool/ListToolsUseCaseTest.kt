package com.suarvis.butler.core.usecase.tool

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.turn.TurnSettings
import com.suarvis.butler.core.fixtures.FakeMcpGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListToolsUseCaseTest {
    private val mcpTool =
        ToolDefinition(
            name = ToolName("mcp__docs__search"),
            description = "Search the team's docs.",
            inputSchema = JsonText("""{"type":"object"}"""),
            tier = PermissionTier.DANGER_FULL_ACCESS,
        )

    @Test
    fun `lists the built-in tools in Clawcode's order, then the MCP servers' tools`() {
        val list = ListToolsUseCase(TurnSettings(), FakeMcpGateway(tools = listOf(mcpTool))).execute()

        assertEquals(
            listOf("bash", "read_file", "write_file", "edit_file", "glob_search", "grep_search"),
            list.builtIn.map { it.name.value },
        )
        assertEquals(PermissionTier.DANGER_FULL_ACCESS, list.builtIn.first().tier)
        assertEquals(listOf(mcpTool), list.external)
    }

    @Test
    fun `without MCP servers only the built-in tools are listed`() {
        val list = ListToolsUseCase(TurnSettings(), FakeMcpGateway()).execute()

        assertEquals(6, list.builtIn.size)
        assertTrue(list.external.isEmpty())
    }

    @Test
    fun `reports whether tools are switched on`() {
        assertFalse(ListToolsUseCase(TurnSettings(toolsEnabled = false), FakeMcpGateway()).execute().enabled)
        assertTrue(ListToolsUseCase(TurnSettings(toolsEnabled = true), FakeMcpGateway()).execute().enabled)
    }
}
