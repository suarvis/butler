package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionSummary

/**
 * Storage of sessions, scoped per workspace. Implementations decide how (JSONL files,
 * PostgreSQL, ...); the contract only says what.
 *
 * Listings are ordered by most recently updated first, ties broken by id descending.
 * Failures surface as [com.suarvis.butler.core.domain.exception.GatewayException].
 */
interface SessionGateway {
    fun findById(workspace: WorkspaceRoot, id: SessionId): Session?

    fun findLatest(workspace: WorkspaceRoot): Session?

    fun findSummaries(workspace: WorkspaceRoot, page: PageRequest): Page<SessionSummary>

    /** Inserts or replaces the whole session. */
    fun save(session: Session)

    /** Returns false when no such session existed. */
    fun delete(workspace: WorkspaceRoot, id: SessionId): Boolean
}
