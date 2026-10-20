package com.suarvis.butler.secondary.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Local stand-in for a model provider's HTTP API: replays scripted responses (plain or as
 * Server-Sent Events) and records every request, so clients are tested without network or cost.
 */
class FakeProviderServer : AutoCloseable {
    data class Recorded(val path: String, val headers: Map<String, String>, val body: String)

    data class Scripted(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val responses = ConcurrentLinkedQueue<Scripted>()
    val requests = ConcurrentLinkedQueue<Recorded>()

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            requests += Recorded(exchange.requestURI.path, headers, body)
            val reply = responses.poll() ?: Scripted(500, """{"error":{"type":"api_error","message":"no scripted response"}}""")
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun enqueue(response: Scripted): FakeProviderServer = apply { responses += response }

    /** Named events (`event:` + `data:`), as the Anthropic Messages API sends them. */
    fun enqueueNamedEvents(vararg events: Pair<String, String>): FakeProviderServer =
        enqueue(Scripted(200, events.joinToString(separator = "") { (name, data) -> "event: $name\ndata: $data\n\n" }, SSE))

    /** Data-only events, as OpenAI-compatible servers send them. */
    fun enqueueDataEvents(vararg data: String): FakeProviderServer =
        enqueue(Scripted(200, data.joinToString(separator = "") { "data: $it\n\n" }, SSE))

    override fun close() = server.stop(0)

    private companion object {
        val SSE = mapOf("content-type" to "text/event-stream")
    }
}

/** Scripted Anthropic Messages API streams. */
object AnthropicStreams {
    fun textAnswer(vararg chunks: String, inputTokens: Int = 12, outputTokens: Int = 5): Array<Pair<String, String>> =
        buildList {
            add("message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6","content":[],"stop_reason":null,"usage":{"input_tokens":$inputTokens,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""")
            add("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            add("ping" to """{"type":"ping"}""")
            chunks.forEach { add("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}""") }
            add("content_block_stop" to """{"type":"content_block_stop","index":0}""")
            add("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":$outputTokens}}""")
            add("message_stop" to """{"type":"message_stop"}""")
        }.toTypedArray()
}

/** Scripted OpenAI Chat Completions streams. */
object OpenAiStreams {
    fun chunk(delta: String, finishReason: String? = null): String =
        """{"id":"chatcmpl-1","object":"chat.completion.chunk","model":"gpt-5.5","choices":[{"index":0,"delta":$delta,"finish_reason":${finishReason?.let { "\"$it\"" } ?: "null"}}]}"""

    fun usage(prompt: Int, completion: Int, cached: Int = 0): String =
        """{"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion},"prompt_tokens_details":{"cached_tokens":$cached}}}"""

    fun textAnswer(vararg chunks: String, prompt: Int = 20, completion: Int = 4, cached: Int = 0): Array<String> =
        buildList {
            add(chunk("""{"role":"assistant","content":""}"""))
            chunks.forEach { add(chunk("""{"content":"$it"}""")) }
            add(chunk("{}", finishReason = "stop"))
            add(usage(prompt, completion, cached))
            add("[DONE]")
        }.toTypedArray()
}

/** Scripted OpenAI Responses API streams (named events, as OpenAI and xAI send them). */
object OpenAiResponsesStreams {
    fun event(type: String, body: String): Pair<String, String> = type to """{"type":"$type",$body}"""

    fun completed(output: String, input: Int = 30, outputTokens: Int = 6, cached: Int = 0, status: String = "completed", incompleteReason: String? = null): Pair<String, String> {
        val incomplete = incompleteReason?.let { """{"reason":"$it"}""" } ?: "null"
        val type = if (status == "incomplete") "response.incomplete" else "response.completed"
        return event(
            type,
            """"response":{"id":"resp_1","object":"response","status":"$status","incomplete_details":$incomplete,"output":$output,""" +
                """"usage":{"input_tokens":$input,"input_tokens_details":{"cached_tokens":$cached},"output_tokens":$outputTokens,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":${input + outputTokens}}}""",
        )
    }

    fun message(text: String): String =
        """{"type":"message","id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":"$text","annotations":[]}]}"""

    fun textAnswer(vararg chunks: String, input: Int = 30, outputTokens: Int = 6, cached: Int = 0): Array<Pair<String, String>> =
        buildList {
            add(event("response.created", """"response":{"id":"resp_1","object":"response","status":"in_progress","output":[],"usage":null}"""))
            add(event("response.output_item.added", """"output_index":0,"item":{"type":"message","id":"msg_1","status":"in_progress","role":"assistant","content":[]}"""))
            chunks.forEach { add(event("response.output_text.delta", """"item_id":"msg_1","output_index":0,"content_index":0,"delta":"$it"""")) }
            add(event("response.output_item.done", """"output_index":0,"item":${message(chunks.joinToString(""))}"""))
            add(completed("[${message(chunks.joinToString(""))}]", input, outputTokens, cached))
        }.toTypedArray()
}
