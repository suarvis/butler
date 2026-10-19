package com.suarvis.butler.secondary.client.anthropic.support

import com.suarvis.butler.secondary.client.anthropic.dto.AnthropicBlock
import com.suarvis.butler.secondary.client.anthropic.dto.AnthropicMessage
import com.suarvis.butler.secondary.client.anthropic.dto.AnthropicUsage
import com.suarvis.butler.secondary.client.support.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Raised when the stream itself carries an `error` event (e.g. overloaded mid-response). */
class AnthropicStreamErrorException(val errorType: String, message: String) : RuntimeException(message)

/**
 * Builds an [AnthropicMessage] from the Messages API event stream
 * (message_start, content_block_start/delta/stop, message_delta, message_stop, ping, error).
 */
class AnthropicStreamAccumulator(
    private val onTextDelta: (String) -> Unit,
    private val onThinkingDelta: (String) -> Unit,
) {
    private sealed interface Builder

    private class TextBuilder(val text: StringBuilder) : Builder

    private class ThinkingBuilder(val thinking: StringBuilder, var signature: String?) : Builder

    private class ToolUseBuilder(val id: String, val name: String, val input: StringBuilder) : Builder

    private val blocks = sortedMapOf<Int, Builder>()
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cacheCreationInputTokens = 0L
    private var cacheReadInputTokens = 0L
    private var stopReason: String? = null

    fun accept(event: SseEvent) {
        val json = Json.parseToJsonElement(event.data).jsonObject
        when (json.string("type") ?: event.event) {
            "message_start" -> json["message"]?.jsonObject?.get("usage")?.jsonObject?.let(::applyUsage)
            "content_block_start" -> start(json.index(), json.getValue("content_block").jsonObject)
            "content_block_delta" -> delta(json.index(), json.getValue("delta").jsonObject)
            "message_delta" -> {
                json["delta"]?.jsonObject?.string("stop_reason")?.let { stopReason = it }
                json["usage"]?.jsonObject?.let(::applyUsage)
            }
            "error" -> {
                val error = json["error"]?.jsonObject
                throw AnthropicStreamErrorException(error?.string("type") ?: "error", error?.string("message") ?: event.data)
            }
            else -> Unit
        }
    }

    fun result(): AnthropicMessage =
        AnthropicMessage(
            blocks =
                blocks.values.map { builder ->
                    when (builder) {
                        is TextBuilder -> AnthropicBlock.Text(builder.text.toString())
                        is ThinkingBuilder -> AnthropicBlock.Thinking(builder.thinking.toString(), builder.signature)
                        is ToolUseBuilder -> AnthropicBlock.ToolUse(builder.id, builder.name, builder.input.toString().ifBlank { "{}" })
                    }
                },
            usage = AnthropicUsage(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens),
            stopReason = stopReason,
        )

    private fun start(index: Int, block: JsonObject) {
        when (block.string("type")) {
            "text" -> blocks[index] = TextBuilder(StringBuilder(block.string("text").orEmpty()))
            "thinking" -> blocks[index] = ThinkingBuilder(StringBuilder(block.string("thinking").orEmpty()), block.string("signature"))
            "tool_use" -> blocks[index] = ToolUseBuilder(block.string("id").orEmpty(), block.string("name").orEmpty(), StringBuilder())
            // redacted_thinking, server tool results, ...: not used by Butler yet.
            else -> Unit
        }
    }

    private fun delta(index: Int, delta: JsonObject) {
        val builder = blocks[index] ?: return
        when (delta.string("type")) {
            "text_delta" ->
                if (builder is TextBuilder) {
                    val text = delta.string("text").orEmpty()
                    builder.text.append(text)
                    onTextDelta(text)
                }
            "thinking_delta" ->
                if (builder is ThinkingBuilder) {
                    val text = delta.string("thinking").orEmpty()
                    builder.thinking.append(text)
                    onThinkingDelta(text)
                }
            "signature_delta" -> if (builder is ThinkingBuilder) builder.signature = delta.string("signature")
            "input_json_delta" -> if (builder is ToolUseBuilder) builder.input.append(delta.string("partial_json").orEmpty())
        }
    }

    /** message_start carries input counts; message_delta carries the running output count. */
    private fun applyUsage(usage: JsonObject) {
        usage.long("input_tokens")?.let { inputTokens = it }
        usage.long("output_tokens")?.let { outputTokens = it }
        usage.long("cache_creation_input_tokens")?.let { cacheCreationInputTokens = it }
        usage.long("cache_read_input_tokens")?.let { cacheReadInputTokens = it }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.index(): Int = getValue("index").jsonPrimitive.int
}
