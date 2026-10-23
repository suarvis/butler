package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.gateway.CommandGateway

/**
 * Stand-in until lane F's sandboxed shell replaces it. Runs nothing and says so in an error
 * outcome, so the model can carry on without the command.
 */
class UnavailableCommandGatewayImpl : CommandGateway {
    override fun run(
        workspace: WorkspaceRoot,
        command: ToolInvocation.Bash,
        sandbox: SandboxPolicy,
        cancellation: TurnCancellation,
    ): ToolOutcome = ToolOutcome("${BuiltInTools.BASH.name} is not available in this build yet", isError = true)
}
