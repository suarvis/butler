package com.suarvis.butler.api.cli.render

import com.suarvis.butler.api.cli.mapper.ToolCliMapper
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.usecase.tool.ListToolsUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** MCP tools join the table after the built-in ones, aligned, with one line of description each. */
class ToolTextRendererTest {
    private val mcpTool =
        ToolDefinition(
            name = ToolName("mcp__docs__search_everything"),
            description = "Search the team's docs.\n\nReturns up to 20 matches.",
            inputSchema = JsonText("""{"type":"object"}"""),
            tier = PermissionTier.DANGER_FULL_ACCESS,
        )

    @Test
    fun `MCP tools follow the built-in ones in an aligned table`() {
        val output = ToolCliMapper.toOutput(ListToolsUseCase.ToolList(enabled = true, builtIn = listOf(BuiltInTools.READ_FILE), external = listOf(mcpTool)))

        assertEquals(listOf("built-in", "mcp"), output.tools.map { it.source })
        assertEquals(
            """
            Available tools (2):
              read_file                     read-only           Read a text file from the workspace.
              mcp__docs__search_everything  danger-full-access  Search the team's docs.
            """.trimIndent(),
            ToolTextRenderer.list(output),
        )
    }
}
