package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.SessionGateway
import java.time.Clock

class StartSessionUseCase(
    private val sessions: SessionGateway,
    private val identity: IdentityGateway,
    private val clock: Clock,
) {
    fun execute(workspace: WorkspaceRoot, model: ModelId? = null): Session {
        val session = Session.start(identity.nextSessionId(), workspace, model, clock.instant())
        sessions.save(session)
        return session
    }
}
