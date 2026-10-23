package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.gateway.ToolInputGateway

/**
 * Stand-in until lane C's real decoder replaces it. Refusing every input keeps the application
 * wired while no tool can run yet, and fails the same way a real decoder fails on bad input.
 */
class UnavailableToolInputGatewayImpl : ToolInputGateway {
    override fun decode(workspace: WorkspaceRoot, name: ToolName, input: JsonText): ToolInvocation =
        throw InvalidToolInputException(name, "tools are not available in this build yet")
}
