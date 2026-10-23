package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.gateway.WorkspaceGateway

/**
 * Stand-in until lane C's real file tools replace it. Answers with an error outcome rather than
 * throwing, so the model learns the tool is missing and the transcript stays valid.
 */
class UnavailableWorkspaceGatewayImpl : WorkspaceGateway {
    override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome =
        ToolOutcome("${toolName(invocation)} is not available in this build yet", isError = true)

    // A private member rather than a top-level function: a top-level one would compile to a
    // `...GatewayImplKt` class, which the ArchUnit naming rule for this package rejects.
    private fun toolName(invocation: ToolInvocation): ToolName =
        when (invocation) {
            is ToolInvocation.ReadFile -> BuiltInTools.READ_FILE.name
            is ToolInvocation.GlobSearch -> BuiltInTools.GLOB_SEARCH.name
            is ToolInvocation.GrepSearch -> BuiltInTools.GREP_SEARCH.name
            is ToolInvocation.WriteFile -> BuiltInTools.WRITE_FILE.name
            is ToolInvocation.EditFile -> BuiltInTools.EDIT_FILE.name
            is ToolInvocation.Bash -> BuiltInTools.BASH.name
            is ToolInvocation.External -> invocation.tool
        }
}
