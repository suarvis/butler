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
import com.suarvis.butler.secondary.persistence.postgresql.mapper.SessionPostgresqlPersistenceMapper
import com.suarvis.butler.secondary.persistence.postgresql.repository.SessionJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import org.flywaydb.core.api.FlywayException
import org.springframework.dao.DataAccessException
import org.springframework.transaction.support.TransactionTemplate

class PostgresqlSessionGatewayImpl(
    private val repository: SessionJdbcRepository,
    private val transactions: TransactionTemplate,
    private val schema: PostgresqlSchema,
) : SessionGateway {
    override fun findById(workspace: WorkspaceRoot, id: SessionId): Session? =
        storageCall("read session $id") {
            repository.findSession(workspace.toString(), id.value)?.let { record ->
                SessionPostgresqlPersistenceMapper.toDomain(record, repository.findMessages(record.id), repository.findPrompts(record.id))
            }
        }

    override fun findLatest(workspace: WorkspaceRoot): Session? =
        storageCall("find the latest session") { repository.findLatestSessionId(workspace.toString()) }
            ?.let { findById(workspace, SessionId(it)) }

    override fun findSummaries(workspace: WorkspaceRoot, page: PageRequest): Page<SessionSummary> =
        storageCall("list sessions") {
            val total = repository.countSessions(workspace.toString())
            val items =
                repository.findSummaries(workspace.toString(), page.offset, page.limit)
                    .map(SessionPostgresqlPersistenceMapper::toSummary)
            Page(items, total, page)
        }

    /** One transaction per aggregate, so a session is never stored half-written. */
    override fun save(session: Session) {
        storageCall("save session ${session.id}") {
            transactions.executeWithoutResult {
                repository.upsertSession(SessionPostgresqlPersistenceMapper.toSessionRecord(session))
                repository.replaceMessages(session.id.value, SessionPostgresqlPersistenceMapper.toMessageRecords(session))
                repository.replacePrompts(session.id.value, SessionPostgresqlPersistenceMapper.toPromptRecords(session))
            }
        }
    }

    override fun delete(workspace: WorkspaceRoot, id: SessionId): Boolean =
        storageCall("delete session $id") { repository.deleteSession(workspace.toString(), id.value) }

    private inline fun <T> storageCall(action: String, block: () -> T): T =
        try {
            schema.ensureMigrated()
            block()
        } catch (error: FlywayException) {
            throw GatewayException("Could not $action: database schema migration failed (${error.message})", error)
        } catch (error: DataAccessException) {
            throw GatewayException("Could not $action: ${error.mostSpecificCause.message}", error)
        } catch (error: GatewayException) {
            throw error
        } catch (error: ButlerException) {
            throw GatewayException("Could not $action: stored data is invalid (${error.message})", error)
        } catch (error: IllegalArgumentException) {
            throw GatewayException("Could not $action: stored data is invalid (${error.message})", error)
        }
}
