package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.SessionSummary
import com.suarvis.butler.core.gateway.SessionGateway

class ListSessionsUseCase(private val sessions: SessionGateway) {
    fun execute(workspace: WorkspaceRoot, page: PageRequest = PageRequest.firstPage()): Page<SessionSummary> =
        sessions.findSummaries(workspace, page)

    fun count(workspace: WorkspaceRoot): Long = sessions.findSummaries(workspace, PageRequest.firstPage(limit = 1)).totalItems
}
