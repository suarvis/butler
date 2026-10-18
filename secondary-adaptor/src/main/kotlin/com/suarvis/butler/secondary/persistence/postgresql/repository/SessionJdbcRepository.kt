package com.suarvis.butler.secondary.persistence.postgresql.repository

import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionMessageRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionPromptRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionSummaryRecord
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** SQL for the session tables. Callers own transactions. */
class SessionJdbcRepository(private val jdbc: JdbcClient) {
    fun findSession(workspaceRoot: String, id: String): SessionRecord? =
        jdbc.sql("select $SESSION_COLUMNS from butler_session s where s.workspace_root = :workspace and s.id = :id")
            .param("workspace", workspaceRoot)
            .param("id", id)
            .query(sessionMapper)
            .optional()
            .orElse(null)

    fun findLatestSessionId(workspaceRoot: String): String? =
        jdbc.sql(
            """
            select s.id from butler_session s
            where s.workspace_root = :workspace
            order by s.updated_at desc, s.id desc
            limit 1
            """.trimIndent(),
        )
            .param("workspace", workspaceRoot)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun countSessions(workspaceRoot: String): Long =
        jdbc.sql("select count(*) from butler_session where workspace_root = :workspace")
            .param("workspace", workspaceRoot)
            .query(Long::class.javaObjectType)
            .single()

    fun findSummaries(workspaceRoot: String, offset: Int, limit: Int): List<SessionSummaryRecord> =
        jdbc.sql(
            """
            select $SESSION_COLUMNS,
                   (select count(*) from butler_session_message m where m.session_id = s.id) as message_count
            from butler_session s
            where s.workspace_root = :workspace
            order by s.updated_at desc, s.id desc
            offset :offset limit :limit
            """.trimIndent(),
        )
            .param("workspace", workspaceRoot)
            .param("offset", offset)
            .param("limit", limit)
            .query(RowMapper { rs, _ -> SessionSummaryRecord(mapSession(rs), rs.getInt("message_count")) })
            .list()

    fun findMessages(sessionId: String): List<SessionMessageRecord> =
        jdbc.sql(
            """
            select session_id, ordinal, role, blocks::text as blocks, usage::text as usage
            from butler_session_message where session_id = :id order by ordinal
            """.trimIndent(),
        )
            .param("id", sessionId)
            .query(
                RowMapper { rs, _ ->
                    SessionMessageRecord(
                        sessionId = rs.getString("session_id"),
                        ordinal = rs.getInt("ordinal"),
                        role = rs.getString("role"),
                        blocksJson = rs.getString("blocks"),
                        usageJson = rs.getString("usage"),
                    )
                },
            )
            .list()

    fun findPrompts(sessionId: String): List<SessionPromptRecord> =
        jdbc.sql("select session_id, ordinal, entered_at, text from butler_session_prompt where session_id = :id order by ordinal")
            .param("id", sessionId)
            .query(
                RowMapper { rs, _ ->
                    SessionPromptRecord(
                        sessionId = rs.getString("session_id"),
                        ordinal = rs.getInt("ordinal"),
                        enteredAt = rs.instant("entered_at"),
                        text = rs.getString("text"),
                    )
                },
            )
            .list()

    fun upsertSession(record: SessionRecord) {
        jdbc.sql(
            """
            insert into butler_session (id, workspace_root, model, created_at, updated_at, fork_parent_session_id,
                                        fork_branch_name, compaction_count, compaction_removed_message_count, compaction_summary)
            values (:id, :workspace, :model, :createdAt, :updatedAt, :forkParent, :forkBranch,
                    :compactionCount, :compactionRemoved, :compactionSummary)
            on conflict (id) do update set
                workspace_root = excluded.workspace_root,
                model = excluded.model,
                updated_at = excluded.updated_at,
                fork_parent_session_id = excluded.fork_parent_session_id,
                fork_branch_name = excluded.fork_branch_name,
                compaction_count = excluded.compaction_count,
                compaction_removed_message_count = excluded.compaction_removed_message_count,
                compaction_summary = excluded.compaction_summary
            """.trimIndent(),
        )
            .param("id", record.id)
            .param("workspace", record.workspaceRoot)
            .param("model", record.model)
            .param("createdAt", record.createdAt.toOffsetDateTime())
            .param("updatedAt", record.updatedAt.toOffsetDateTime())
            .param("forkParent", record.forkParentSessionId)
            .param("forkBranch", record.forkBranchName)
            .param("compactionCount", record.compactionCount)
            .param("compactionRemoved", record.compactionRemovedMessageCount)
            .param("compactionSummary", record.compactionSummary)
            .update()
    }

    /**
     * Replaces the transcript. Sessions are saved as whole aggregates; append-only writes are an
     * optimisation for later if transcripts grow large.
     */
    fun replaceMessages(sessionId: String, messages: List<SessionMessageRecord>) {
        jdbc.sql("delete from butler_session_message where session_id = :id").param("id", sessionId).update()
        messages.forEach { message ->
            jdbc.sql(
                """
                insert into butler_session_message (session_id, ordinal, role, blocks, usage)
                values (:id, :ordinal, :role, cast(:blocks as jsonb), cast(:usage as jsonb))
                """.trimIndent(),
            )
                .param("id", message.sessionId)
                .param("ordinal", message.ordinal)
                .param("role", message.role)
                .param("blocks", message.blocksJson)
                .param("usage", message.usageJson)
                .update()
        }
    }

    fun replacePrompts(sessionId: String, prompts: List<SessionPromptRecord>) {
        jdbc.sql("delete from butler_session_prompt where session_id = :id").param("id", sessionId).update()
        prompts.forEach { prompt ->
            jdbc.sql("insert into butler_session_prompt (session_id, ordinal, entered_at, text) values (:id, :ordinal, :enteredAt, :text)")
                .param("id", prompt.sessionId)
                .param("ordinal", prompt.ordinal)
                .param("enteredAt", prompt.enteredAt.toOffsetDateTime())
                .param("text", prompt.text)
                .update()
        }
    }

    fun deleteSession(workspaceRoot: String, id: String): Boolean =
        jdbc.sql("delete from butler_session where workspace_root = :workspace and id = :id")
            .param("workspace", workspaceRoot)
            .param("id", id)
            .update() > 0

    private val sessionMapper = RowMapper { rs, _ -> mapSession(rs) }

    private fun mapSession(rs: ResultSet): SessionRecord =
        SessionRecord(
            id = rs.getString("id"),
            workspaceRoot = rs.getString("workspace_root"),
            model = rs.getString("model"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
            forkParentSessionId = rs.getString("fork_parent_session_id"),
            forkBranchName = rs.getString("fork_branch_name"),
            compactionCount = rs.getObject("compaction_count", Int::class.javaObjectType),
            compactionRemovedMessageCount = rs.getObject("compaction_removed_message_count", Int::class.javaObjectType),
            compactionSummary = rs.getString("compaction_summary"),
        )

    private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val SESSION_COLUMNS =
            "s.id, s.workspace_root, s.model, s.created_at, s.updated_at, s.fork_parent_session_id, s.fork_branch_name, " +
                "s.compaction_count, s.compaction_removed_message_count, s.compaction_summary"
    }
}
