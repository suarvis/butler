package com.suarvis.butler.secondary.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Scripted model replies, streamed as each provider streams them. This file exists twice,
 * identical apart from its package line: in secondary-adaptor's test sources
 * (secondary/client/ScriptedStreams.kt) and in api-primary-adaptor's (api/cli/support/ScriptedStreams.kt).
 * The api tests cannot see secondary-adaptor's test classes, and the adaptor tests and the CLI gate
 * checks must parse the same streams. Change both copies together: api's ScriptedFakesTest fails
 * when they differ, and secondary's ScriptedProviderStreamsTest parses every stream through the real gateways.
 */

/**
 * One tool call in a scripted reply. The arguments stream in [chunks]; by default they are cut into
 * three pieces in the middle of tokens, because joining fragments is where clients break (Clawcode's
 * `grep_chunk_assembly` scenario).
 */
data class ScriptedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val chunks: List<String> = splitArguments(argumentsJson),
) {
    init {
        require(chunks.joinToString(separator = "") == argumentsJson) { "The chunks of $name ($id) must join to its arguments" }
    }

    companion object {
        private const val PIECES = 3

        /** A call whose arguments stream in exactly these pieces. */
        fun chunked(id: String, name: String, vararg chunks: String): ScriptedToolCall =
            ScriptedToolCall(id, name, chunks.joinToString(separator = ""), chunks.toList())

        private fun splitArguments(json: String): List<String> {
            val count = minOf(PIECES, json.length)
            if (count < 2) return listOf(json)
            return (0 until count).map { json.substring(it * json.length / count, (it + 1) * json.length / count) }
        }
    }
}

/**
 * Token counts a scripted reply reports. [inputTokens] leaves out cache reads, and the OpenAI
 * dialects add [cachedTokens] back into their prompt counts, so every dialect yields the same
 * `TokenUsage`. The defaults are the counts the CLI tests' Anthropic fake always reported.
 */
data class ScriptedUsage(val inputTokens: Int = 12, val outputTokens: Int = 5, val cachedTokens: Int = 0)

/** One scripted model reply: text, or one or more tool calls, optionally after some text. */
sealed interface ScriptedReply {
    val usage: ScriptedUsage

    data class Text(val chunks: List<String>, override val usage: ScriptedUsage = ScriptedUsage()) : ScriptedReply

    data class ToolCalls(
        val calls: List<ScriptedToolCall>,
        val text: String = "",
        override val usage: ScriptedUsage = ScriptedUsage(),
    ) : ScriptedReply {
        init {
            require(calls.isNotEmpty()) { "A tool-call reply needs at least one call" }
        }
    }

    companion object {
        fun text(vararg chunks: String): ScriptedReply = Text(chunks.toList())

        fun toolCalls(vararg calls: ScriptedToolCall): ScriptedReply = ToolCalls(calls.toList())
    }
}

/**
 * A tool result as a request carried it. [isError] is null in the OpenAI dialects, which have no error
 * flag; on Anthropic a missing `is_error` reads as false, the API's default.
 */
data class NativeToolResult(val callId: String, val output: String, val isError: Boolean?)

/** The three wire dialects Butler's provider clients speak, with the path each is served on. */
enum class ProviderDialect(val path: String) {
    ANTHROPIC_MESSAGES("/v1/messages"),
    CHAT_COMPLETIONS("/v1/chat/completions"),
    RESPONSES("/v1/responses"),
    ;

    /** The whole Server-Sent Events body this dialect streams for [reply]. */
    fun sse(reply: ScriptedReply): String =
        when (this) {
            ANTHROPIC_MESSAGES -> ScriptedStreams.namedEvents(ScriptedStreams.anthropic(reply))
            CHAT_COMPLETIONS -> ScriptedStreams.dataEvents(ScriptedStreams.chatCompletions(reply))
            RESPONSES -> ScriptedStreams.namedEvents(ScriptedStreams.responses(reply))
        }

    /** Names of the tools a request offered, in order. */
    fun offeredToolNames(body: JsonObject): List<String> =
        body.objects("tools").map { tool ->
            when (this) {
                CHAT_COMPLETIONS -> (tool["function"] as? JsonObject)?.string("name").orEmpty()
                ANTHROPIC_MESSAGES, RESPONSES -> tool.string("name").orEmpty()
            }
        }

    /** Ids of the earlier tool calls a request replays in its history, in order. */
    fun replayedToolCallIds(body: JsonObject): List<String> =
        when (this) {
            ANTHROPIC_MESSAGES -> body.objects("messages").flatMap { it.objects("content") }.filter { it.string("type") == "tool_use" }.map { it.string("id").orEmpty() }
            CHAT_COMPLETIONS -> body.objects("messages").flatMap { it.objects("tool_calls") }.map { it.string("id").orEmpty() }
            RESPONSES -> body.objects("input").filter { it.string("type") == "function_call" }.map { it.string("call_id").orEmpty() }
        }

    /** Tool results a request carries in this dialect's native form, in order. */
    fun toolResults(body: JsonObject): List<NativeToolResult> =
        when (this) {
            ANTHROPIC_MESSAGES ->
                body.objects("messages").flatMap { it.objects("content") }.filter { it.string("type") == "tool_result" }.map {
                    NativeToolResult(it.string("tool_use_id").orEmpty(), anthropicContent(it["content"]), (it["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
            CHAT_COMPLETIONS ->
                body.objects("messages").filter { it.string("role") == "tool" }.map {
                    NativeToolResult(it.string("tool_call_id").orEmpty(), it.string("content").orEmpty(), isError = null)
                }
            RESPONSES ->
                body.objects("input").filter { it.string("type") == "function_call_output" }.map {
                    NativeToolResult(it.string("call_id").orEmpty(), it.string("output").orEmpty(), isError = null)
                }
        }
}

/**
 * Builds each dialect's stream for a [ScriptedReply], in the shapes the providers document and
 * Butler's stream accumulators parse.
 */
object ScriptedStreams {
    const val ANTHROPIC_MODEL = "claude-sonnet-4-6"
    const val OPENAI_MODEL = "gpt-5.5"
    private const val MESSAGE_ID = "msg_1"
    private const val RESPONSE_ID = "resp_1"

    fun namedEvents(events: List<Pair<String, String>>): String = events.joinToString(separator = "") { (name, data) -> "event: $name\ndata: $data\n\n" }

    fun dataEvents(data: List<String>): String = data.joinToString(separator = "") { "data: $it\n\n" }

    /**
     * Anthropic Messages: `message_start`, one content block per text and per `tool_use` call (its
     * input in `input_json_delta` pieces), `message_delta` with stop reason `tool_use` or `end_turn`,
     * then `message_stop`. Tool calls follow Clawcode's mock-anthropic-service (`tool_uses_sse`).
     */
    fun anthropic(reply: ScriptedReply): List<Pair<String, String>> {
        val events = mutableListOf<Pair<String, JsonObject>>()
        events +=
            "message_start" to
            buildJsonObject {
                put("type", "message_start")
                putJsonObject("message") {
                    put("id", MESSAGE_ID)
                    put("type", "message")
                    put("role", "assistant")
                    put("model", ANTHROPIC_MODEL)
                    put("content", JsonArray(emptyList()))
                    put("stop_reason", JsonNull)
                    put("stop_sequence", JsonNull)
                    putJsonObject("usage") {
                        put("input_tokens", reply.usage.inputTokens)
                        put("output_tokens", 1)
                        put("cache_creation_input_tokens", 0)
                        put("cache_read_input_tokens", reply.usage.cachedTokens)
                    }
                }
            }
        events += "ping" to buildJsonObject { put("type", "ping") }
        var index = 0
        textChunks(reply)?.let { chunks ->
            val block = index++
            events += "content_block_start" to anthropicBlockEvent("content_block_start", block, "content_block", textBlock())
            chunks.forEach { events += "content_block_delta" to anthropicBlockEvent("content_block_delta", block, "delta", textDelta(it)) }
            events += "content_block_stop" to anthropicBlockEvent("content_block_stop", block)
        }
        toolCalls(reply).forEach { call ->
            val block = index++
            events += "content_block_start" to anthropicBlockEvent("content_block_start", block, "content_block", toolUseBlock(call))
            call.chunks.forEach { events += "content_block_delta" to anthropicBlockEvent("content_block_delta", block, "delta", inputJsonDelta(it)) }
            events += "content_block_stop" to anthropicBlockEvent("content_block_stop", block)
        }
        events +=
            "message_delta" to
            buildJsonObject {
                put("type", "message_delta")
                putJsonObject("delta") {
                    put("stop_reason", if (reply is ScriptedReply.ToolCalls) "tool_use" else "end_turn")
                    put("stop_sequence", JsonNull)
                }
                putJsonObject("usage") { put("output_tokens", reply.usage.outputTokens) }
            }
        events += "message_stop" to buildJsonObject { put("type", "message_stop") }
        return events.map { (name, data) -> name to data.toString() }
    }

    /**
     * Chat Completions: data-only chunks with `choices[0].delta`; each call opens with its `id`,
     * `type` and `function.name`, then its `function.arguments` arrive in pieces under the same
     * `index`. A `finish_reason` chunk (`tool_calls` or `stop`), a usage chunk and `[DONE]` close it.
     */
    fun chatCompletions(reply: ScriptedReply): List<String> {
        val text = textChunks(reply)
        val chunks = mutableListOf<String>()
        chunks +=
            chatChunk(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", if (text == null) JsonNull else JsonPrimitive(""))
                },
            )
        text?.forEach { chunks += chatChunk(buildJsonObject { put("content", it) }) }
        toolCalls(reply).forEachIndexed { index, call ->
            chunks += chatChunk(toolCallsDelta(index, call.id, call.name, ""))
            call.chunks.forEach { chunks += chatChunk(toolCallsDelta(index, id = null, name = null, arguments = it)) }
        }
        chunks += chatChunk(JsonObject(emptyMap()), finishReason = if (reply is ScriptedReply.ToolCalls) "tool_calls" else "stop")
        chunks +=
            buildJsonObject {
                put("id", "chatcmpl-1")
                put("object", "chat.completion.chunk")
                put("model", OPENAI_MODEL)
                put("choices", JsonArray(emptyList()))
                putJsonObject("usage") {
                    put("prompt_tokens", reply.usage.inputTokens + reply.usage.cachedTokens)
                    put("completion_tokens", reply.usage.outputTokens)
                    put("total_tokens", reply.usage.inputTokens + reply.usage.cachedTokens + reply.usage.outputTokens)
                    putJsonObject("prompt_tokens_details") { put("cached_tokens", reply.usage.cachedTokens) }
                }
            }.toString()
        chunks += "[DONE]"
        return chunks
    }

    /**
     * Responses API: `response.created`, then per output item `response.output_item.added`, its
     * deltas (`response.output_text.delta`, or `response.function_call_arguments.delta` and
     * `.done` for a `function_call`) and `response.output_item.done`; `response.completed` carries
     * every item and the usage.
     */
    fun responses(reply: ScriptedReply): List<Pair<String, String>> {
        val events = mutableListOf<Pair<String, String>>()

        fun emit(type: String, body: JsonObjectBuilder.() -> Unit) {
            val sequence = events.size
            events +=
                type to
                buildJsonObject {
                    put("type", type)
                    put("sequence_number", sequence)
                    body()
                }.toString()
        }

        val items = mutableListOf<JsonObject>()
        emit("response.created") {
            putJsonObject("response") {
                put("id", RESPONSE_ID)
                put("object", "response")
                put("status", "in_progress")
                put("output", JsonArray(emptyList()))
                put("usage", JsonNull)
            }
        }
        textChunks(reply)?.let { chunks ->
            val outputIndex = items.size
            emit("response.output_item.added") {
                put("output_index", outputIndex)
                put("item", responsesMessage("in_progress", text = null))
            }
            chunks.forEach { chunk ->
                emit("response.output_text.delta") {
                    put("item_id", MESSAGE_ID)
                    put("output_index", outputIndex)
                    put("content_index", 0)
                    put("delta", chunk)
                }
            }
            val item = responsesMessage("completed", chunks.joinToString(separator = ""))
            emit("response.output_item.done") {
                put("output_index", outputIndex)
                put("item", item)
            }
            items += item
        }
        toolCalls(reply).forEach { call ->
            val outputIndex = items.size
            // The item id differs from call_id, as at OpenAI, so a client that links results by the wrong one fails.
            val itemId = "fc_${outputIndex + 1}"
            emit("response.output_item.added") {
                put("output_index", outputIndex)
                put("item", functionCallItem(itemId, call, arguments = "", status = "in_progress"))
            }
            call.chunks.forEach { chunk ->
                emit("response.function_call_arguments.delta") {
                    put("item_id", itemId)
                    put("output_index", outputIndex)
                    put("delta", chunk)
                }
            }
            emit("response.function_call_arguments.done") {
                put("item_id", itemId)
                put("output_index", outputIndex)
                put("arguments", call.argumentsJson)
            }
            val item = functionCallItem(itemId, call, call.argumentsJson, status = "completed")
            emit("response.output_item.done") {
                put("output_index", outputIndex)
                put("item", item)
            }
            items += item
        }
        emit("response.completed") {
            putJsonObject("response") {
                put("id", RESPONSE_ID)
                put("object", "response")
                put("status", "completed")
                put("incomplete_details", JsonNull)
                put("output", JsonArray(items))
                putJsonObject("usage") {
                    put("input_tokens", reply.usage.inputTokens + reply.usage.cachedTokens)
                    putJsonObject("input_tokens_details") { put("cached_tokens", reply.usage.cachedTokens) }
                    put("output_tokens", reply.usage.outputTokens)
                    putJsonObject("output_tokens_details") { put("reasoning_tokens", 0) }
                    put("total_tokens", reply.usage.inputTokens + reply.usage.cachedTokens + reply.usage.outputTokens)
                }
            }
        }
        return events
    }

    /** The text pieces of a reply's text block, or null when it has none. */
    private fun textChunks(reply: ScriptedReply): List<String>? =
        when (reply) {
            is ScriptedReply.Text -> reply.chunks
            is ScriptedReply.ToolCalls -> reply.text.takeIf { it.isNotEmpty() }?.let { listOf(it) }
        }

    private fun toolCalls(reply: ScriptedReply): List<ScriptedToolCall> = (reply as? ScriptedReply.ToolCalls)?.calls.orEmpty()

    private fun anthropicBlockEvent(type: String, index: Int, key: String? = null, value: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("type", type)
            put("index", index)
            if (key != null && value != null) put(key, value)
        }

    private fun textBlock(): JsonObject =
        buildJsonObject {
            put("type", "text")
            put("text", "")
        }

    private fun textDelta(text: String): JsonObject =
        buildJsonObject {
            put("type", "text_delta")
            put("text", text)
        }

    private fun toolUseBlock(call: ScriptedToolCall): JsonObject =
        buildJsonObject {
            put("type", "tool_use")
            put("id", call.id)
            put("name", call.name)
            put("input", JsonObject(emptyMap()))
        }

    private fun inputJsonDelta(partialJson: String): JsonObject =
        buildJsonObject {
            put("type", "input_json_delta")
            put("partial_json", partialJson)
        }

    private fun chatChunk(delta: JsonObject, finishReason: String? = null): String =
        buildJsonObject {
            put("id", "chatcmpl-1")
            put("object", "chat.completion.chunk")
            put("model", OPENAI_MODEL)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", finishReason)
                }
            }
        }.toString()

    /** Only the first fragment of a call carries its id, type and name, as OpenAI streams them. */
    private fun toolCallsDelta(index: Int, id: String?, name: String?, arguments: String): JsonObject =
        buildJsonObject {
            putJsonArray("tool_calls") {
                addJsonObject {
                    put("index", index)
                    if (id != null) {
                        put("id", id)
                        put("type", "function")
                    }
                    putJsonObject("function") {
                        if (name != null) put("name", name)
                        put("arguments", arguments)
                    }
                }
            }
        }

    private fun responsesMessage(status: String, text: String?): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("id", MESSAGE_ID)
            put("status", status)
            put("role", "assistant")
            putJsonArray("content") {
                if (text != null) {
                    addJsonObject {
                        put("type", "output_text")
                        put("text", text)
                        put("annotations", JsonArray(emptyList()))
                    }
                }
            }
        }

    private fun functionCallItem(itemId: String, call: ScriptedToolCall, arguments: String, status: String): JsonObject =
        buildJsonObject {
            put("type", "function_call")
            put("id", itemId)
            put("call_id", call.id)
            put("name", call.name)
            put("arguments", arguments)
            put("status", status)
        }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Anthropic tool_result content is a string or a list of text blocks. */
private fun anthropicContent(content: JsonElement?): String =
    when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { it as? JsonObject }.filter { it.string("type") == "text" }.joinToString(separator = "") { it.string("text").orEmpty() }
        else -> ""
    }
