package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.SessionGateway
import java.time.Clock

class ForkSessionUseCase(
    private val getSession: GetSessionUseCase,
    private val sessions: SessionGateway,
    private val identity: IdentityGateway,
    private val clock: Clock,
) {
    fun execute(workspace: WorkspaceRoot, source: SessionReference, branchName: BranchName? = null): Session {
        val parent = getSession.execute(workspace, source)
        val forked = parent.fork(identity.nextSessionId(), branchName, clock.instant())
        sessions.save(forked)
        return forked
    }
}
