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
import com.suarvis.butler.secondary.client.openai.dto.OpenAiCompletion
import com.suarvis.butler.secondary.client.openai.dto.OpenAiRequestOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Domain ↔ OpenAI Chat Completions wire format. The transcript arrives normalized by core
 * (TranscriptNormalizer), so this only translates shapes: the system prompt becomes the first
 * message, tool calls ride on the assistant message, and each tool result is its own `tool` message.
 */
object OpenAiClientMapper {
    fun toRequestBody(request: ModelRequest, options: OpenAiRequestOptions): JsonObject =
        buildJsonObject {
            put("model", request.model.apiModelName)
            put("stream", true)
            if (options.streamUsage) putJsonObject("stream_options") { put("include_usage", true) }
            // Unknown limits are left to the server: a guess above a model's limit is rejected outright.
            request.model.maxOutputTokens?.let { put(options.maxTokensParameter, it) }
            put("messages", JsonArray(toWireMessages(request)))
        }

    fun toResponse(completion: OpenAiCompletion): ModelResponse =
        ModelResponse(
            blocks =
                buildList<ContentBlock> {
                    if (completion.reasoning.isNotEmpty()) add(ContentBlock.Thinking(completion.reasoning, signature = null))
                    if (completion.text.isNotEmpty()) add(ContentBlock.Text(completion.text))
                    completion.toolCalls.forEach {
                        add(ContentBlock.ToolUse(ToolCallId(it.id), ToolName(it.name), JsonText(it.argumentsJson.ifBlank { "{}" })))
                    }
                },
            usage =
                TokenUsage(
                    // Stored the Anthropic way (cache reads apart from input) so costs and totals add up across providers.
                    inputTokens = (completion.usage.promptTokens - completion.usage.cachedTokens).coerceAtLeast(0),
                    outputTokens = completion.usage.completionTokens,
                    cacheReadInputTokens = completion.usage.cachedTokens,
                ),
            stopReason = toStopReason(completion.finishReason),
        )

    fun toStopReason(raw: String?): StopReason =
        when (raw) {
            "stop" -> StopReason.END_TURN
            "length" -> StopReason.MAX_TOKENS
            "tool_calls", "function_call" -> StopReason.TOOL_USE
            "content_filter" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

    private fun toWireMessages(request: ModelRequest): List<JsonObject> =
        buildList {
            if (request.systemPrompt.isNotBlank()) add(textMessage("system", request.systemPrompt))
            request.messages.forEach { addAll(toWire(it)) }
        }

    private fun toWire(message: ConversationMessage): List<JsonObject> =
        when (message.role) {
            MessageRole.SYSTEM -> emptyList()
            MessageRole.USER -> listOfNotNull(message.text.takeIf { it.isNotEmpty() }?.let { textMessage("user", it) })
            MessageRole.ASSISTANT -> listOfNotNull(assistantMessage(message))
            MessageRole.TOOL ->
                message.toolResults.map { result ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.toolUseId.value)
                        // Chat Completions has no error flag on tool results.
                        put("content", if (result.isError) "[error] ${result.output}" else result.output)
                    }
                }
        }

    private fun assistantMessage(message: ConversationMessage): JsonObject? {
        val text = message.text
        val calls = message.toolUses
        if (text.isEmpty() && calls.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            if (text.isEmpty()) put("content", JsonNull) else put("content", text)
            if (calls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JsonArray(
                        calls.map { call ->
                            buildJsonObject {
                                put("id", call.id.value)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name.value)
                                    put("arguments", call.input.value)
                                }
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun textMessage(role: String, text: String): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", text)
        }
}
