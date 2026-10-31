package com.suarvis.butler.api.cli.render

import com.suarvis.butler.api.cli.dto.ToolListOutput

/**
 * `butler tools` as text: Clawcode's `Available tools (N):` heading, then one row per tool with
 * its name, tier and description. Only a description's first line is shown, so a long MCP
 * description cannot break the table; JSON output carries it whole.
 */
object ToolTextRenderer {
    fun list(output: ToolListOutput): String =
        buildString {
            appendLine("Available tools (${output.count}):")
            val nameWidth = output.tools.maxOfOrNull { it.name.length } ?: 0
            val tierWidth = output.tools.maxOfOrNull { it.tier.length } ?: 0
            output.tools.forEach { tool ->
                val description = tool.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                appendLine("  ${tool.name.padEnd(nameWidth)}  ${tool.tier.padEnd(tierWidth)}  $description")
            }
            if (!output.enabled) {
                appendLine()
                appendLine("Tools are switched off: turns offer the model none of these until butler.tools.enabled is true (BUTLER_TOOLS_ENABLED=true).")
            }
        }.trimEnd()
}
