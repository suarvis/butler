package com.suarvis.butler.secondary.client.anthropic

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** Local stand-in for api.anthropic.com: replays scripted HTTP responses and records requests. */
class FakeAnthropicServer : AutoCloseable {
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
            val reply = responses.poll() ?: Scripted(500, """{"type":"error","error":{"type":"api_error","message":"no scripted response"}}""")
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun enqueue(response: Scripted): FakeAnthropicServer = apply { responses += response }

    fun enqueueStream(vararg events: Pair<String, String>): FakeAnthropicServer =
        enqueue(Scripted(200, events.joinToString(separator = "") { (name, data) -> "event: $name\ndata: $data\n\n" }, mapOf("content-type" to "text/event-stream")))

    override fun close() = server.stop(0)

    companion object {
        /** A complete streamed answer saying [chunks] joined, with the given usage. */
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
}
