package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.gateway.ProjectContextGateway

/**
 * Stand-in until lane G's instruction-file and git reader replaces it. An empty context leaves the
 * system prompt as it is today.
 */
class NoProjectContextGatewayImpl : ProjectContextGateway {
    override fun load(workspace: WorkspaceRoot): ProjectContext = ProjectContext.EMPTY
}
