package com.suarvis.butler.secondary.client.openai.mapper

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
import com.suarvis.butler.secondary.client.openai.dto.ResponsesOutputItem
import com.suarvis.butler.secondary.client.openai.dto.ResponsesResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Domain ↔ OpenAI Responses API wire format. The transcript arrives normalized by core
 * (TranscriptNormalizer), so this only translates shapes: messages become input items, a tool
 * call becomes a `function_call` item and its result a `function_call_output` item.
 */
object OpenAiResponsesClientMapper {
    fun toRequestBody(request: ModelRequest): JsonObject =
        buildJsonObject {
            put("model", request.model.apiModelName)
            put("stream", true)
            // Butler keeps the conversation itself, so nothing needs to be stored at the provider.
            put("store", false)
            // Unknown limits are left to the provider: a guess above a model's limit is rejected outright.
            request.model.maxOutputTokens?.let { put("max_output_tokens", it) }
            put("input", JsonArray(toInput(request)))
        }

    fun toResponse(result: ResponsesResult): ModelResponse =
        ModelResponse(
            blocks = result.items.flatMap(::toBlocks),
            usage =
                TokenUsage(
                    // Stored the Anthropic way (cache reads apart from input) so costs and totals add up across providers.
                    inputTokens = (result.usage.promptTokens - result.usage.cachedTokens).coerceAtLeast(0),
                    outputTokens = result.usage.completionTokens,
                    cacheReadInputTokens = result.usage.cachedTokens,
                ),
            stopReason = toStopReason(result),
        )

    fun toStopReason(result: ResponsesResult): StopReason =
        when {
            result.items.any { it is ResponsesOutputItem.Message && it.refusal != null } -> StopReason.REFUSAL
            result.status == "incomplete" && result.incompleteReason == "max_output_tokens" -> StopReason.MAX_TOKENS
            result.status == "incomplete" && result.incompleteReason == "content_filter" -> StopReason.REFUSAL
            result.items.any { it is ResponsesOutputItem.FunctionCall } -> StopReason.TOOL_USE
            result.status == null || result.status == "completed" -> StopReason.END_TURN
            else -> StopReason.OTHER
        }

    private fun toBlocks(item: ResponsesOutputItem): List<ContentBlock> =
        when (item) {
            is ResponsesOutputItem.Reasoning -> listOfNotNull(item.summary.takeIf { it.isNotBlank() }?.let { ContentBlock.Thinking(it, signature = null) })
            is ResponsesOutputItem.Message ->
                listOfNotNull(item.text.takeIf { it.isNotEmpty() }?.let(ContentBlock::Text), item.refusal?.let(ContentBlock::Text))
            is ResponsesOutputItem.FunctionCall ->
                listOf(ContentBlock.ToolUse(ToolCallId(item.callId), ToolName(item.name), JsonText(item.argumentsJson.ifBlank { "{}" })))
        }

    /** The system prompt goes in as a system message rather than `instructions`, which not every Responses server accepts. */
    private fun toInput(request: ModelRequest): List<JsonObject> =
        buildList {
            if (request.systemPrompt.isNotBlank()) add(message("system", request.systemPrompt))
            request.messages.forEach { addAll(toItems(it)) }
        }

    private fun toItems(message: ConversationMessage): List<JsonObject> =
        when (message.role) {
            MessageRole.SYSTEM -> emptyList()
            MessageRole.USER -> listOfNotNull(message.text.takeIf { it.isNotEmpty() }?.let { message("user", it) })
            MessageRole.ASSISTANT ->
                listOfNotNull(message.text.takeIf { it.isNotEmpty() }?.let { message("assistant", it) }) +
                    message.toolUses.map { call ->
                        buildJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id.value)
                            put("name", call.name.value)
                            put("arguments", call.input.value)
                        }
                    }
            MessageRole.TOOL ->
                message.toolResults.map { result ->
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.toolUseId.value)
                        // The Responses API has no error flag on tool output.
                        put("output", if (result.isError) "[error] ${result.output}" else result.output)
                    }
                }
        }

    private fun message(role: String, text: String): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", text)
        }
}
