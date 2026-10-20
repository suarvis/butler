package com.suarvis.butler.secondary.client.openai.support

import com.suarvis.butler.secondary.client.openai.dto.OpenAiUsage
import com.suarvis.butler.secondary.client.openai.dto.ResponsesOutputItem
import com.suarvis.butler.secondary.client.openai.dto.ResponsesResult
import com.suarvis.butler.secondary.client.support.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Raised for a `response.failed` or `error` event: the stream itself reports the failure. */
class OpenAiResponsesStreamErrorException(val code: String?, message: String) : RuntimeException(message)

/**
 * Builds a [ResponsesResult] from Responses API stream events. Deltas (`response.output_text.delta`,
 * reasoning summary deltas) are for live display; the result comes from the complete items, taken
 * from `response.completed`/`response.incomplete` and, should those not carry them, from each
 * `response.output_item.done`. Event types Butler does not use are ignored, so new ones do not break it.
 */
class OpenAiResponsesStreamAccumulator(
    private val onTextDelta: (String) -> Unit,
    private val onThinkingDelta: (String) -> Unit,
) {
    private val doneItems = sortedMapOf<Int, ResponsesOutputItem>()
    private val streamedText = sortedMapOf<Int, StringBuilder>()
    private var finalItems: List<ResponsesOutputItem>? = null
    private var status: String? = null
    private var incompleteReason: String? = null
    private var usage = OpenAiUsage()

    fun accept(event: SseEvent) {
        val json = Json.parseToJsonElement(event.data) as? JsonObject ?: return
        when (json.string("type") ?: event.event) {
            "response.output_text.delta" ->
                json.string("delta")?.takeIf { it.isNotEmpty() }?.let {
                    streamedText.getOrPut(json.int("output_index") ?: 0) { StringBuilder() }.append(it)
                    onTextDelta(it)
                }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                json.string("delta")?.takeIf { it.isNotEmpty() }?.let(onThinkingDelta)
            "response.output_item.done" ->
                (json["item"] as? JsonObject)?.let(::toItem)?.let { doneItems[json.int("output_index") ?: doneItems.size] = it }
            "response.completed", "response.incomplete" -> (json["response"] as? JsonObject)?.let(::finish)
            "response.failed" -> {
                val error = (json["response"] as? JsonObject)?.get("error") as? JsonObject
                throw OpenAiResponsesStreamErrorException(error?.string("code"), error?.string("message") ?: "The response failed")
            }
            "error" -> {
                val nested = json["error"] as? JsonObject
                throw OpenAiResponsesStreamErrorException(
                    nested?.string("code") ?: json.string("code"),
                    nested?.string("message") ?: json.string("message") ?: event.data,
                )
            }
            else -> Unit
        }
    }

    fun result(): ResponsesResult {
        val items =
            finalItems
                ?: doneItems.values.toList().ifEmpty { streamedText.values.map { ResponsesOutputItem.Message(it.toString(), refusal = null) } }
        return ResponsesResult(items, status, incompleteReason, usage)
    }

    private fun finish(response: JsonObject) {
        status = response.string("status")
        incompleteReason = (response["incomplete_details"] as? JsonObject)?.string("reason")
        (response["usage"] as? JsonObject)?.let { usage = toUsage(it) }
        val output = (response["output"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::toItem) }
        if (!output.isNullOrEmpty()) finalItems = output
    }

    private fun toItem(item: JsonObject): ResponsesOutputItem? =
        when (item.string("type")) {
            "message" -> {
                val parts = (item["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                ResponsesOutputItem.Message(
                    text = parts.filter { it.string("type") == "output_text" }.joinToString("") { it.string("text").orEmpty() },
                    refusal = parts.filter { it.string("type") == "refusal" }.joinToString("") { it.string("refusal").orEmpty() }.ifEmpty { null },
                )
            }
            "function_call" ->
                ResponsesOutputItem.FunctionCall(
                    // call_id links the call to its output; the item id is only a fallback for lax servers.
                    callId = item.string("call_id") ?: item.string("id") ?: "call_${doneItems.size}",
                    name = item.string("name").orEmpty(),
                    argumentsJson = item.string("arguments").orEmpty(),
                )
            "reasoning" -> {
                val texts = (item["summary"] as? JsonArray).orEmpty() + (item["content"] as? JsonArray).orEmpty()
                ResponsesOutputItem.Reasoning(texts.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("\n"))
            }
            else -> null
        }

    private fun toUsage(json: JsonObject): OpenAiUsage =
        OpenAiUsage(
            promptTokens = json.long("input_tokens") ?: 0,
            completionTokens = json.long("output_tokens") ?: 0,
            cachedTokens = (json["input_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0,
        )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
