package com.suarvis.butler.secondary.persistence.postgresql.entity

import java.time.Instant

/** Row of `butler_session`. */
data class SessionRecord(
    val id: String,
    val workspaceRoot: String,
    val model: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val forkParentSessionId: String?,
    val forkBranchName: String?,
    val compactionCount: Int?,
    val compactionRemovedMessageCount: Int?,
    val compactionSummary: String?,
)

/** Row of `butler_session_message`; `blocksJson` and `usageJson` are JSONB documents. */
data class SessionMessageRecord(
    val sessionId: String,
    val ordinal: Int,
    val role: String,
    val blocksJson: String,
    val usageJson: String?,
)

/** Row of `butler_session_prompt`. */
data class SessionPromptRecord(
    val sessionId: String,
    val ordinal: Int,
    val enteredAt: Instant,
    val text: String,
)

/** Projection used by listings: a session row plus its message count. */
data class SessionSummaryRecord(
    val session: SessionRecord,
    val messageCount: Int,
)
