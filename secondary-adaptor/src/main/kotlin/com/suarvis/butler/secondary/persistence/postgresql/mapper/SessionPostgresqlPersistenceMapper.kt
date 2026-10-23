package com.suarvis.butler.secondary.persistence.postgresql.mapper

import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.PromptEntry
import com.suarvis.butler.core.domain.session.ReasoningReplay
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionCompaction
import com.suarvis.butler.core.domain.session.SessionFork
import com.suarvis.butler.core.domain.session.SessionSummary
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionMessageRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionPromptRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionRecord
import com.suarvis.butler.secondary.persistence.postgresql.entity.SessionSummaryRecord
import com.suarvis.butler.secondary.persistence.postgresql.support.BlockDocument
import com.suarvis.butler.secondary.persistence.postgresql.support.ContentJsonCodec
import com.suarvis.butler.secondary.persistence.postgresql.support.ReasoningReplayDocument
import com.suarvis.butler.secondary.persistence.postgresql.support.UsageDocument
import java.nio.file.Path

/** Domain [Session] ↔ PostgreSQL rows. */
object SessionPostgresqlPersistenceMapper {
    fun toSessionRecord(session: Session): SessionRecord =
        SessionRecord(
            id = session.id.value,
            workspaceRoot = session.workspace.toString(),
            model = session.model?.value,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            forkParentSessionId = session.fork?.parentSessionId?.value,
            forkBranchName = session.fork?.branchName?.value,
            compactionCount = session.compaction?.count,
            compactionRemovedMessageCount = session.compaction?.removedMessageCount,
            compactionSummary = session.compaction?.summary,
        )

    fun toMessageRecords(session: Session): List<SessionMessageRecord> =
        session.messages.mapIndexed { ordinal, message ->
            SessionMessageRecord(
                sessionId = session.id.value,
                ordinal = ordinal,
                role = message.role.name.lowercase(),
                blocksJson = ContentJsonCodec.encodeBlocks(message.blocks.map(::toDocument)),
                usageJson =
                    message.usage?.let {
                        ContentJsonCodec.encodeUsage(
                            UsageDocument(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens, it.cacheReadInputTokens),
                        )
                    },
            )
        }

    fun toPromptRecords(session: Session): List<SessionPromptRecord> =
        session.promptHistory.mapIndexed { ordinal, prompt ->
            SessionPromptRecord(session.id.value, ordinal, prompt.enteredAt, prompt.text)
        }

    fun toDomain(
        session: SessionRecord,
        messages: List<SessionMessageRecord>,
        prompts: List<SessionPromptRecord>,
    ): Session =
        Session.restore(
            id = SessionId(session.id),
            workspace = workspaceOf(session),
            model = session.model?.let(::ModelId),
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messages = messages.sortedBy { it.ordinal }.map(::toDomain),
            compaction = compactionOf(session),
            fork = forkOf(session),
            promptHistory = prompts.sortedBy { it.ordinal }.map { PromptEntry(it.enteredAt, it.text) },
        )

    fun toSummary(record: SessionSummaryRecord): SessionSummary =
        SessionSummary(
            id = SessionId(record.session.id),
            workspace = workspaceOf(record.session),
            model = record.session.model?.let(::ModelId),
            createdAt = record.session.createdAt,
            updatedAt = record.session.updatedAt,
            messageCount = record.messageCount,
            fork = forkOf(record.session),
        )

    private fun workspaceOf(record: SessionRecord): WorkspaceRoot = WorkspaceRoot.of(Path.of(record.workspaceRoot))

    private fun forkOf(record: SessionRecord): SessionFork? =
        record.forkParentSessionId?.let { SessionFork(SessionId(it), record.forkBranchName?.let(::BranchName)) }

    private fun compactionOf(record: SessionRecord): SessionCompaction? {
        val count = record.compactionCount ?: return null
        return SessionCompaction(count, record.compactionRemovedMessageCount ?: 0, record.compactionSummary.orEmpty())
    }

    private fun toDomain(record: SessionMessageRecord): ConversationMessage =
        ConversationMessage(
            role = MessageRole.valueOf(record.role.uppercase()),
            blocks = ContentJsonCodec.decodeBlocks(record.blocksJson).map(::toDomain),
            usage =
                record.usageJson?.let(ContentJsonCodec::decodeUsage)?.let {
                    TokenUsage(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens, it.cacheReadInputTokens)
                },
        )

    private fun toDocument(block: ContentBlock): BlockDocument =
        when (block) {
            is ContentBlock.Text -> BlockDocument.Text(block.text)
            is ContentBlock.Thinking ->
                BlockDocument.Thinking(
                    block.thinking,
                    block.signature,
                    block.replay?.let { ReasoningReplayDocument(it.provider.value, it.itemId, it.payload) },
                )
            is ContentBlock.ToolUse -> BlockDocument.ToolUse(block.id.value, block.name.value, block.input.value)
            is ContentBlock.ToolResult ->
                BlockDocument.ToolResult(block.toolUseId.value, block.toolName.value, block.output, block.isError)
        }

    private fun toDomain(document: BlockDocument): ContentBlock =
        when (document) {
            is BlockDocument.Text -> ContentBlock.Text(document.text)
            is BlockDocument.Thinking ->
                ContentBlock.Thinking(
                    document.thinking,
                    document.signature,
                    document.replay?.let { ReasoningReplay(ProviderId(it.provider), it.itemId, it.payload) },
                )
            is BlockDocument.ToolUse -> ContentBlock.ToolUse(ToolCallId(document.id), ToolName(document.name), JsonText(document.input))
            is BlockDocument.ToolResult ->
                ContentBlock.ToolResult(ToolCallId(document.toolUseId), ToolName(document.toolName), document.output, document.isError)
        }
}
