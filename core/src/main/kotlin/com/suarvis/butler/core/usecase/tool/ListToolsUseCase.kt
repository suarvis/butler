package com.suarvis.butler.core.usecase.tool

import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.turn.TurnSettings
import com.suarvis.butler.core.gateway.McpGateway

/**
 * The tools a turn can offer the model, for `butler tools`: the built-in tools in Clawcode's order,
 * then those of the configured MCP servers. Read-only; it never starts or calls a tool.
 */
class ListToolsUseCase(private val settings: TurnSettings, private val mcp: McpGateway) {
    /** @param enabled whether turns offer the model tools at all (`butler.tools.enabled`) */
    data class ToolList(val enabled: Boolean, val builtIn: List<ToolDefinition>, val external: List<ToolDefinition>)

    fun execute(): ToolList = ToolList(settings.toolsEnabled, BuiltInTools.ALL, mcp.tools())
}
