package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionSummary
import com.suarvis.butler.core.gateway.SessionGateway

/** Fake gateway for infrastructure-free tests. Honours the ordering rules of the real contract. */
class InMemorySessionGateway : SessionGateway {
    private val sessions = linkedMapOf<Pair<WorkspaceRoot, SessionId>, Session>()

    val saved: List<Session>
        get() = sessions.values.toList()

    override fun findById(workspace: WorkspaceRoot, id: SessionId): Session? = sessions[workspace to id]

    override fun findLatest(workspace: WorkspaceRoot): Session? = ordered(workspace).firstOrNull()

    override fun findSummaries(workspace: WorkspaceRoot, page: PageRequest): Page<SessionSummary> {
        val all = ordered(workspace)
        val items = all.drop(page.offset).take(page.limit).map(Session::summary)
        return Page(items, all.size.toLong(), page)
    }

    override fun save(session: Session) {
        sessions[session.workspace to session.id] = session
    }

    override fun delete(workspace: WorkspaceRoot, id: SessionId): Boolean = sessions.remove(workspace to id) != null

    private fun ordered(workspace: WorkspaceRoot): List<Session> =
        sessions.values
            .filter { it.workspace == workspace }
            .sortedWith(compareByDescending<Session> { it.updatedAt }.thenByDescending { it.id.value })
}
