package com.suarvis.butler.secondary.client.openai.support

import com.suarvis.butler.secondary.client.openai.dto.OpenAiCompletion
import com.suarvis.butler.secondary.client.openai.dto.OpenAiToolCall
import com.suarvis.butler.secondary.client.openai.dto.OpenAiUsage
import com.suarvis.butler.secondary.client.support.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Raised when a chunk carries an `error` object instead of choices (some servers fail mid-stream this way). */
class OpenAiStreamErrorException(val code: String?, message: String) : RuntimeException(message)

/**
 * Builds an [OpenAiCompletion] from Chat Completions stream chunks: `choices[0].delta` with
 * `content`, `reasoning_content`/`reasoning` (DeepSeek, OpenRouter, Ollama) and `tool_calls`
 * fragments, `finish_reason`, and a final `usage` chunk; `[DONE]` ends the stream.
 */
class OpenAiStreamAccumulator(
    private val onTextDelta: (String) -> Unit,
    private val onThinkingDelta: (String) -> Unit,
) {
    private class ToolCallBuilder(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = sortedMapOf<Int, ToolCallBuilder>()
    private var finishReason: String? = null
    private var usage = OpenAiUsage()

    fun accept(event: SseEvent) {
        if (event.data.trim() == DONE) return
        val chunk = Json.parseToJsonElement(event.data) as? JsonObject ?: return
        chunk["error"]?.takeUnless { it is JsonNull }?.let { throw streamError(it) }
        (chunk["usage"] as? JsonObject)?.let { usage = toUsage(it) }
        val choice =
            (chunk["choices"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it.int("index") ?: 0) == 0 }
                ?: return
        choice.string("finish_reason")?.let { finishReason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        delta.string("content")?.takeIf { it.isNotEmpty() }?.let {
            text.append(it)
            onTextDelta(it)
        }
        (delta.string("reasoning_content") ?: delta.string("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
            reasoning.append(it)
            onThinkingDelta(it)
        }
        (delta["tool_calls"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach(::applyToolCallDelta)
    }

    fun result(): OpenAiCompletion =
        OpenAiCompletion(
            reasoning = reasoning.toString(),
            text = text.toString(),
            // Some local servers omit call ids; the position keeps them distinct within the answer.
            toolCalls = toolCalls.map { (index, call) -> OpenAiToolCall(call.id ?: "call_$index", call.name, call.arguments.toString()) },
            finishReason = finishReason,
            usage = usage,
        )

    private fun applyToolCallDelta(delta: JsonObject) {
        val id = delta.string("id")?.takeIf { it.isNotEmpty() }
        // `index` ties fragments together; servers that omit it send each call whole, with an id.
        val index = delta.int("index") ?: if (id != null || toolCalls.isEmpty()) toolCalls.size else toolCalls.lastKey()
        val call = toolCalls.getOrPut(index) { ToolCallBuilder() }
        id?.let { call.id = it }
        val function = delta["function"] as? JsonObject ?: return
        function.string("name")?.takeIf { call.name.isEmpty() }?.let { call.name = it }
        function.string("arguments")?.let { call.arguments.append(it) }
    }

    private fun toUsage(json: JsonObject): OpenAiUsage {
        val details = json["prompt_tokens_details"] as? JsonObject
        return OpenAiUsage(
            promptTokens = json.long("prompt_tokens") ?: 0,
            completionTokens = json.long("completion_tokens") ?: 0,
            // DeepSeek reports cache hits under its own name.
            cachedTokens = details?.long("cached_tokens") ?: json.long("prompt_cache_hit_tokens") ?: 0,
        )
    }

    private fun streamError(error: JsonElement): OpenAiStreamErrorException =
        when (error) {
            is JsonObject -> OpenAiStreamErrorException(error.string("code") ?: error.string("type"), error.string("message") ?: error.toString())
            else -> OpenAiStreamErrorException(null, (error as? JsonPrimitive)?.contentOrNull ?: error.toString())
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val DONE = "[DONE]"
    }
}
