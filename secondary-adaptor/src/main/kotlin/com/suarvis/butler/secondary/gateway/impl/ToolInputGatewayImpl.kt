package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.secondary.workspace.mapper.ToolInputWorkspaceMapper

/**
 * Decodes the six built-in tools against their schemas. MCP tools (`mcp__server__tool`) pass
 * through untouched apart from the permission subject picked out of their input (core does not
 * parse JSON), so the agent loop routes them to MCP without this adaptor knowing MCP exists;
 * any other name is refused, because the model can only have invented it.
 */
class ToolInputGatewayImpl : ToolInputGateway {
    override fun decode(workspace: WorkspaceRoot, name: ToolName, input: JsonText): ToolInvocation =
        when {
            name.value.startsWith(MCP_PREFIX) -> ToolInvocation.External(name, input, ToolInputWorkspaceMapper.mcpSubject(input))
            BuiltInTools.byName(name) != null -> ToolInputWorkspaceMapper.toInvocation(workspace, name, input)
            else -> throw InvalidToolInputException(name, "unknown tool")
        }

    private companion object {
        const val MCP_PREFIX = "mcp__"
    }
}
