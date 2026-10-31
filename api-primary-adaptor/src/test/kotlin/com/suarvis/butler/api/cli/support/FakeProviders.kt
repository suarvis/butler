package com.suarvis.butler.api.cli.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** A request a fake received. Header names are lower-case. */
data class RecordedRequest(val path: String, val body: String, val headers: Map<String, String>) {
    val authorization: String?
        get() = headers["authorization"]

    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
}

/**
 * Local fake of a model provider for the CLI end-to-end tests. Each request takes the next queued
 * reply, streamed in the dialect of the path it was sent to ([ScriptedStreams]), and every request
 * is recorded in arrival order. A gate check queues tool calls and then the final text, runs the
 * CLI, and reads the tool result from the second request with [ProviderDialect.toolResults].
 */
abstract class ScriptedProviderServer(
    contextPath: String,
    private val dialectOf: (path: String) -> ProviderDialect,
) : AutoCloseable {
    private sealed interface Queued {
        data class Reply(val reply: ScriptedReply) : Queued

        data class Failure(val status: Int, val body: String) : Queued
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val queue = ConcurrentLinkedQueue<Queued>()
    private val received = ConcurrentLinkedQueue<RecordedRequest>()

    /** The server's root URL, without a path. */
    protected val rootUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    /** The value for `butler.providers.<id>.base-url`. */
    abstract val baseUrl: String

    /** Every request so far, oldest first. */
    val recorded: List<RecordedRequest>
        get() = received.toList()

    init {
        server.createContext(contextPath) { exchange -> respond(exchange) }
        server.start()
    }

    /** Queues [replies] in order: for example tool calls, then the final text. */
    fun reply(vararg replies: ScriptedReply): ScriptedProviderServer = apply { replies.forEach { queue += Queued.Reply(it) } }

    /** Queues a text answer streamed in these pieces. */
    fun answer(vararg chunks: String): ScriptedProviderServer = reply(ScriptedReply.Text(chunks.toList()))

    /** Queues one reply making these tool calls. */
    fun callTools(vararg calls: ScriptedToolCall): ScriptedProviderServer = reply(ScriptedReply.ToolCalls(calls.toList()))

    /** Queues an HTTP error with this body. */
    fun fail(status: Int, body: String): ScriptedProviderServer = apply { queue += Queued.Failure(status, body) }

    /** Request bodies parsed as JSON, oldest first; [path] keeps only one endpoint's, e.g. [ProviderDialect.path]. */
    fun requestBodies(path: String? = null): List<JsonObject> = recorded.filter { path == null || it.path == path }.map { it.json() }

    override fun close() = server.stop(0)

    private fun respond(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString(",") }
        received += RecordedRequest(path, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), headers)
        val next: Queued? = queue.poll()
        val (status, contentType, body) =
            when (next) {
                is Queued.Reply -> Triple(200, "text/event-stream", dialectOf(path).sse(next.reply))
                is Queued.Failure -> Triple(next.status, "application/json", next.body)
                null -> Triple(500, "application/json", NO_REPLY)
            }
        exchange.responseHeaders.add("content-type", contentType)
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val NO_REPLY = """{"type":"error","error":{"type":"api_error","message":"no scripted reply"}}"""
    }
}

/** Fake of the Anthropic Messages API: `POST /v1/messages`. */
class FakeAnthropic : ScriptedProviderServer("/v1/messages", { ProviderDialect.ANTHROPIC_MESSAGES }) {
    override val baseUrl: String
        get() = rootUrl

    /** Request bodies, oldest first. */
    val requests: List<String>
        get() = recorded.map { it.body }
}

/**
 * Fake of an OpenAI-style server under `/v1`: `POST /v1/responses` (OpenAI, xAI) answers in the
 * Responses dialect, any other path (`/v1/chat/completions` for Ollama and other compatible
 * servers) in Chat Completions.
 */
class FakeOpenAi : ScriptedProviderServer("/v1/", { path -> if (path.endsWith("/responses")) ProviderDialect.RESPONSES else ProviderDialect.CHAT_COMPLETIONS }) {
    override val baseUrl: String
        get() = "$rootUrl/v1"

    /** Requests with their paths, oldest first. */
    val requests: List<RecordedRequest>
        get() = recorded
}
