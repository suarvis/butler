package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.gateway.SessionGateway

class DeleteSessionUseCase(
    private val getSession: GetSessionUseCase,
    private val sessions: SessionGateway,
) {
    /** Resolves the reference first so `latest` deletes exactly the session the user saw. */
    fun execute(workspace: WorkspaceRoot, reference: SessionReference): SessionId {
        val target = getSession.execute(workspace, reference).id
        if (!sessions.delete(workspace, target)) throw SessionNotFoundException(SessionReference.ById(target))
        return target
    }
}
