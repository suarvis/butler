package com.suarvis.butler.secondary.persistence.postgresql.support

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSONB documents stored in `butler_session_message`. Kept separate from the file store's records
 * on purpose: each persistence technology owns its model, so either can change independently.
 */
object ContentJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private val blocksSerializer = ListSerializer(BlockDocument.serializer())

    fun encodeBlocks(blocks: List<BlockDocument>): String = json.encodeToString(blocksSerializer, blocks)

    fun decodeBlocks(raw: String): List<BlockDocument> = json.decodeFromString(blocksSerializer, raw)

    fun encodeUsage(usage: UsageDocument): String = json.encodeToString(UsageDocument.serializer(), usage)

    fun decodeUsage(raw: String): UsageDocument = json.decodeFromString(UsageDocument.serializer(), raw)
}

@Serializable
sealed class BlockDocument {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : BlockDocument()

    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String? = null) : BlockDocument()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: String) : BlockDocument()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        @SerialName("tool_name") val toolName: String,
        val output: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : BlockDocument()
}

@Serializable
data class UsageDocument(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
)
