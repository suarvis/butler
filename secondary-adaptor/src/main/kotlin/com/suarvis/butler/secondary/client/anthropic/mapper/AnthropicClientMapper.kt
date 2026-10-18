package com.suarvis.butler.secondary.client.anthropic.mapper

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.secondary.client.anthropic.dto.AnthropicBlock
import com.suarvis.butler.secondary.client.anthropic.dto.AnthropicMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Domain ↔ Anthropic Messages API wire format. */
object AnthropicClientMapper {
    fun toRequestBody(request: ModelRequest): JsonObject =
        buildJsonObject {
            put("model", request.model.id.value)
            put("max_tokens", request.model.maxOutputTokens)
            put("stream", true)
            put("system", request.systemPrompt)
            put("messages", JsonArray(toWireMessages(request.messages)))
        }

    fun toResponse(message: AnthropicMessage): ModelResponse =
        ModelResponse(
            blocks = message.blocks.map(::toDomain),
            usage =
                TokenUsage(
                    inputTokens = message.usage.inputTokens,
                    outputTokens = message.usage.outputTokens,
                    cacheCreationInputTokens = message.usage.cacheCreationInputTokens,
                    cacheReadInputTokens = message.usage.cacheReadInputTokens,
                ),
            stopReason = toStopReason(message.stopReason),
        )

    fun toStopReason(raw: String?): StopReason =
        when (raw) {
            "end_turn" -> StopReason.END_TURN
            "max_tokens" -> StopReason.MAX_TOKENS
            "tool_use" -> StopReason.TOOL_USE
            "stop_sequence" -> StopReason.STOP_SEQUENCE
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

    /**
     * Anthropic has no tool role: tool results travel in a user message. Consecutive messages
     * with the same wire role are merged, which the API requires for tool results and which it
     * would otherwise do implicitly.
     */
    private fun toWireMessages(messages: List<ConversationMessage>): List<JsonObject> {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (message in messages) {
            val role =
                when (message.role) {
                    MessageRole.USER, MessageRole.TOOL -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> continue
                }
            val blocks = message.blocks.mapNotNull(::toWireBlock)
            if (blocks.isEmpty()) continue
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }
        return turns.map { (role, blocks) ->
            buildJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        }
    }

    private fun toWireBlock(block: ContentBlock): JsonObject? =
        when (block) {
            // The API rejects empty text blocks.
            is ContentBlock.Text ->
                if (block.text.isEmpty()) {
                    null
                } else {
                    buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    }
                }
            // Thinking is not enabled on requests yet, and replaying thinking blocks requires it.
            is ContentBlock.Thinking -> null
            is ContentBlock.ToolUse ->
                buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.id.value)
                    put("name", block.name.value)
                    put("input", parseObject(block.input.value))
                }
            is ContentBlock.ToolResult ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", block.toolUseId.value)
                    put("content", block.output)
                    put("is_error", block.isError)
                }
        }

    private fun toDomain(block: AnthropicBlock): ContentBlock =
        when (block) {
            is AnthropicBlock.Text -> ContentBlock.Text(block.text)
            is AnthropicBlock.Thinking -> ContentBlock.Thinking(block.thinking, block.signature)
            is AnthropicBlock.ToolUse -> ContentBlock.ToolUse(ToolCallId(block.id), ToolName(block.name), JsonText(block.inputJson))
        }

    /** Tool input must be a JSON object; anything unparsable becomes `{}` rather than a rejected request. */
    private fun parseObject(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
}
