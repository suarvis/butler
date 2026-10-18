package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionSummary
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.persistence.file.mapper.SessionFilePersistenceMapper
import com.suarvis.butler.secondary.persistence.file.repository.SessionFileRepository
import java.io.IOException

/**
 * File-backed [SessionGateway]. Listing reads every session file in the workspace, which is what
 * Clawcode does too; fine for hundreds of sessions, and the PostgreSQL store exists for more.
 */
class FileSessionGatewayImpl(private val repository: SessionFileRepository) : SessionGateway {
    override fun findById(workspace: WorkspaceRoot, id: SessionId): Session? =
        storageCall("read session $id") {
            repository.read(workspace.path, id.value)?.let { SessionFilePersistenceMapper.toDomain(it, workspace) }
        }

    override fun findLatest(workspace: WorkspaceRoot): Session? = ordered(workspace).firstOrNull()

    override fun findSummaries(workspace: WorkspaceRoot, page: PageRequest): Page<SessionSummary> {
        val all = ordered(workspace)
        return Page(all.drop(page.offset).take(page.limit).map(Session::summary), all.size.toLong(), page)
    }

    override fun save(session: Session) {
        storageCall("save session ${session.id}") {
            repository.write(session.workspace.path, session.id.value, SessionFilePersistenceMapper.toRecords(session))
        }
    }

    override fun delete(workspace: WorkspaceRoot, id: SessionId): Boolean =
        storageCall("delete session $id") { repository.delete(workspace.path, id.value) }

    private fun ordered(workspace: WorkspaceRoot): List<Session> =
        storageCall("list sessions in $workspace") {
            repository.readAll(workspace.path).values.map { SessionFilePersistenceMapper.toDomain(it, workspace) }
        }.sortedWith(compareByDescending<Session> { it.updatedAt }.thenByDescending { it.id.value })

    /** Translates storage and format failures into the core's gateway error. */
    private inline fun <T> storageCall(action: String, block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw GatewayException("Could not $action: ${error.message}", error)
        } catch (error: ButlerException) {
            throw GatewayException("Could not $action: stored data is invalid (${error.message})", error)
        } catch (error: RuntimeException) {
            throw GatewayException("Could not $action: ${error.message}", error)
        }
}
