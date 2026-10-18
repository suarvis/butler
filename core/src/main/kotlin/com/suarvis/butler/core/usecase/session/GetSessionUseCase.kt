package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.gateway.SessionGateway

class GetSessionUseCase(private val sessions: SessionGateway) {
    /** @throws SessionNotFoundException when nothing matches the reference. */
    fun execute(workspace: WorkspaceRoot, reference: SessionReference): Session =
        findOrNull(workspace, reference) ?: throw SessionNotFoundException(reference)

    fun findOrNull(workspace: WorkspaceRoot, reference: SessionReference): Session? =
        when (reference) {
            SessionReference.Latest -> sessions.findLatest(workspace)
            is SessionReference.ById -> sessions.findById(workspace, reference.id)
        }
}
