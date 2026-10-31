package com.suarvis.butler.api.cli.mapper

import com.suarvis.butler.api.cli.dto.ToolEntryOutput
import com.suarvis.butler.api.cli.dto.ToolListOutput
import com.suarvis.butler.core.domain.permission.cliName
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.usecase.tool.ListToolsUseCase

/** The tool list → CLI output: built-in tools first, then MCP tools, each with its tier under Clawcode's name. */
object ToolCliMapper {
    private const val BUILT_IN = "built-in"
    private const val MCP = "mcp"

    fun toOutput(list: ListToolsUseCase.ToolList): ToolListOutput {
        val tools = list.builtIn.map { toEntry(it, BUILT_IN) } + list.external.map { toEntry(it, MCP) }
        return ToolListOutput(enabled = list.enabled, tools = tools, count = tools.size)
    }

    private fun toEntry(tool: ToolDefinition, source: String): ToolEntryOutput =
        ToolEntryOutput(name = tool.name.value, tier = tool.tier.cliName, description = tool.description, source = source)
}
