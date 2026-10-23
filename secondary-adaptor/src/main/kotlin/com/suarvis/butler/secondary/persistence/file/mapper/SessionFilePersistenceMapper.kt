package com.suarvis.butler.secondary.persistence.file.mapper

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
import com.suarvis.butler.secondary.persistence.file.entity.BlockRecord
import com.suarvis.butler.secondary.persistence.file.entity.CompactionLineRecord
import com.suarvis.butler.secondary.persistence.file.entity.ForkRecord
import com.suarvis.butler.secondary.persistence.file.entity.MessageLineRecord
import com.suarvis.butler.secondary.persistence.file.entity.MessageRecord
import com.suarvis.butler.secondary.persistence.file.entity.PromptHistoryLineRecord
import com.suarvis.butler.secondary.persistence.file.entity.ReasoningReplayRecord
import com.suarvis.butler.secondary.persistence.file.entity.SessionLineRecord
import com.suarvis.butler.secondary.persistence.file.entity.SessionMetaRecord
import com.suarvis.butler.secondary.persistence.file.entity.TextBlockRecord
import com.suarvis.butler.secondary.persistence.file.entity.ThinkingBlockRecord
import com.suarvis.butler.secondary.persistence.file.entity.ToolResultBlockRecord
import com.suarvis.butler.secondary.persistence.file.entity.ToolUseBlockRecord
import com.suarvis.butler.secondary.persistence.file.entity.UsageRecord
import java.time.Instant

/** Domain [Session] ↔ JSONL records. The only place that knows both shapes. */
object SessionFilePersistenceMapper {
    /** Clawcode's current session format version; written so `claw` can read Butler sessions. */
    const val FORMAT_VERSION = 3

    private val LEGACY_ID_TIMESTAMP = Regex("^session-(\\d{10,})-")

    fun toRecords(session: Session): List<SessionLineRecord> =
        buildList {
            add(
                SessionMetaRecord(
                    version = FORMAT_VERSION,
                    sessionId = session.id.value,
                    createdAtMs = session.createdAt.toEpochMilli(),
                    updatedAtMs = session.updatedAt.toEpochMilli(),
                    fork = session.fork?.let { ForkRecord(it.parentSessionId.value, it.branchName?.value) },
                    workspaceRoot = session.workspace.toString(),
                    model = session.model?.value,
                ),
            )
            session.compaction?.let { add(CompactionLineRecord(it.count, it.removedMessageCount, it.summary)) }
            session.messages.forEach { add(MessageLineRecord(toRecord(it))) }
            session.promptHistory.forEach { add(PromptHistoryLineRecord(it.enteredAt.toEpochMilli(), it.text)) }
        }

    /**
     * @param workspace the workspace the file was found in. It wins over the `workspace_root`
     * written in the file, because a copied or moved repository keeps its sessions.
     */
    fun toDomain(records: List<SessionLineRecord>, workspace: WorkspaceRoot): Session {
        val meta =
            records.filterIsInstance<SessionMetaRecord>().lastOrNull()
                ?: throw IllegalArgumentException("session file has no session_meta record")
        val id = SessionId(meta.sessionId)
        val updatedAt = Instant.ofEpochMilli(meta.updatedAtMs)
        return Session.restore(
            id = id,
            workspace = workspace,
            model = meta.model?.let(::ModelId),
            createdAt = meta.createdAtMs?.let(Instant::ofEpochMilli) ?: legacyCreatedAt(id) ?: updatedAt,
            updatedAt = updatedAt,
            messages = records.filterIsInstance<MessageLineRecord>().map { toDomain(it.message) },
            compaction =
                records.filterIsInstance<CompactionLineRecord>().lastOrNull()
                    ?.let { SessionCompaction(it.count, it.removedMessageCount, it.summary) },
            fork = meta.fork?.let { SessionFork(SessionId(it.parentSessionId), it.branchName?.let(::BranchName)) },
            promptHistory =
                records.filterIsInstance<PromptHistoryLineRecord>()
                    .map { PromptEntry(Instant.ofEpochMilli(it.timestampMs), it.text) },
        )
    }

    private fun toRecord(message: ConversationMessage): MessageRecord =
        MessageRecord(
            role = message.role.name.lowercase(),
            blocks = message.blocks.map(::toRecord),
            usage =
                message.usage?.let {
                    UsageRecord(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens, it.cacheReadInputTokens)
                },
        )

    private fun toRecord(block: ContentBlock): BlockRecord =
        when (block) {
            is ContentBlock.Text -> TextBlockRecord(block.text)
            is ContentBlock.Thinking ->
                ThinkingBlockRecord(
                    block.thinking,
                    block.signature,
                    block.replay?.let { ReasoningReplayRecord(it.provider.value, it.itemId, it.payload) },
                )
            is ContentBlock.ToolUse -> ToolUseBlockRecord(block.id.value, block.name.value, block.input.value)
            is ContentBlock.ToolResult ->
                ToolResultBlockRecord(block.toolUseId.value, block.toolName.value, block.output, block.isError)
        }

    private fun toDomain(record: MessageRecord): ConversationMessage =
        ConversationMessage(
            role = parseRole(record.role),
            blocks = record.blocks.map(::toDomain),
            usage =
                record.usage?.let {
                    TokenUsage(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens, it.cacheReadInputTokens)
                },
        )

    private fun toDomain(record: BlockRecord): ContentBlock =
        when (record) {
            is TextBlockRecord -> ContentBlock.Text(record.text)
            is ThinkingBlockRecord ->
                ContentBlock.Thinking(
                    record.thinking,
                    record.signature,
                    record.replay?.let { ReasoningReplay(ProviderId(it.provider), it.itemId, it.payload) },
                )
            is ToolUseBlockRecord -> ContentBlock.ToolUse(ToolCallId(record.id), ToolName(record.name), JsonText(record.input))
            is ToolResultBlockRecord ->
                ContentBlock.ToolResult(ToolCallId(record.toolUseId), ToolName(record.toolName), record.output, record.isError)
        }

    private fun parseRole(raw: String): MessageRole =
        MessageRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown message role '$raw'")

    /** Older Clawcode files omit created_at_ms; the id embeds the creation time. */
    private fun legacyCreatedAt(id: SessionId): Instant? =
        LEGACY_ID_TIMESTAMP.find(id.value)?.groupValues?.get(1)?.toLongOrNull()?.let(Instant::ofEpochMilli)
}
