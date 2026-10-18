package com.suarvis.butler.secondary.persistence.file.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * File persistence model. One JSON object per line, discriminated by "type", in the same shape as
 * Clawcode's session format version 3 (session_meta / message / compaction / prompt_history), so
 * sessions written by `claw` can be read by Butler and the other way round.
 */

@Serializable
sealed interface SessionLineRecord

@Serializable
@SerialName("session_meta")
data class SessionMetaRecord(
    val version: Int,
    @SerialName("session_id") val sessionId: String,
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    val fork: ForkRecord? = null,
    @SerialName("workspace_root") val workspaceRoot: String? = null,
    val model: String? = null,
) : SessionLineRecord

@Serializable
@SerialName("message")
data class MessageLineRecord(val message: MessageRecord) : SessionLineRecord

@Serializable
@SerialName("compaction")
data class CompactionLineRecord(
    val count: Int,
    @SerialName("removed_message_count") val removedMessageCount: Int,
    val summary: String,
) : SessionLineRecord

@Serializable
@SerialName("prompt_history")
data class PromptHistoryLineRecord(
    @SerialName("timestamp_ms") val timestampMs: Long,
    val text: String,
) : SessionLineRecord

@Serializable
data class ForkRecord(
    @SerialName("parent_session_id") val parentSessionId: String,
    @SerialName("branch_name") val branchName: String? = null,
)

@Serializable
data class MessageRecord(
    val role: String,
    val blocks: List<BlockRecord>,
    val usage: UsageRecord? = null,
)

@Serializable
sealed interface BlockRecord

@Serializable
@SerialName("text")
data class TextBlockRecord(val text: String) : BlockRecord

@Serializable
@SerialName("thinking")
data class ThinkingBlockRecord(val thinking: String, val signature: String? = null) : BlockRecord

@Serializable
@SerialName("tool_use")
data class ToolUseBlockRecord(val id: String, val name: String, val input: String) : BlockRecord

@Serializable
@SerialName("tool_result")
data class ToolResultBlockRecord(
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("tool_name") val toolName: String,
    val output: String,
    @SerialName("is_error") val isError: Boolean = false,
) : BlockRecord

@Serializable
data class UsageRecord(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)
