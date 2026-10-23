package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.gateway.McpGateway

/**
 * Stand-in until lane H's MCP client replaces it: no servers, so no tools are offered. A call can
 * still arrive from a model that names a tool it was not offered, so it gets an error outcome.
 */
class NoMcpGatewayImpl : McpGateway {
    override fun tools(): List<ToolDefinition> = emptyList()

    override fun call(tool: ToolName, input: JsonText): ToolOutcome =
        ToolOutcome("$tool is not available: no MCP servers are connected in this build yet", isError = true)
}
