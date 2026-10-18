package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.client.anthropic.FakeAnthropicServer
import com.suarvis.butler.secondary.config.AnthropicProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnthropicModelGatewayImplTest {
    private val server = FakeAnthropicServer()
    private val sonnet = ModelProfile(ModelId("claude-sonnet-4-6"), ProviderKind.ANTHROPIC, 2048, null)

    @AfterEach
    fun stop() = server.close()

    private fun gateway(apiKey: String = "test-key") =
        AnthropicModelGatewayImpl(AnthropicMessagesClient(AnthropicProperties(apiKey = apiKey, baseUrl = server.baseUrl)))

    private fun request(vararg messages: ConversationMessage) = ModelRequest(sonnet, "You are Butler.", messages.toList())

    private fun lastBody(): JsonObject = Json.parseToJsonElement(server.requests.last().body).jsonObject

    @Test
    fun `streams text deltas and assembles the answer with usage`() {
        server.enqueueStream(*FakeAnthropicServer.textAnswer("Hello", " there!"))
        val deltas = mutableListOf<ModelStreamEvent>()

        val response = gateway().stream(request(ConversationMessage.userText("Say hello"))) { deltas += it }

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hello"), ModelStreamEvent.TextDelta(" there!")), deltas)
        assertEquals(listOf(ContentBlock.Text("Hello there!")), response.blocks)
        assertEquals(TokenUsage(inputTokens = 12, outputTokens = 5), response.usage)
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun `sends the Messages API request with key, version and model limits`() {
        server.enqueueStream(*FakeAnthropicServer.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi"))) { }

        val recorded = server.requests.single()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("test-key", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = lastBody()
        assertEquals("claude-sonnet-4-6", body["model"]!!.jsonPrimitive.content)
        assertEquals(2048, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("You are Butler.", body["system"]!!.jsonPrimitive.content)
    }

    @Test
    fun `maps tool calls and results into Anthropic's user and assistant turns`() {
        server.enqueueStream(*FakeAnthropicServer.textAnswer("done"))
        val call = ContentBlock.ToolUse(ToolCallId("toolu_1"), ToolName("read_file"), JsonText("""{"path":"a.txt"}"""))
        val call2 = ContentBlock.ToolUse(ToolCallId("toolu_2"), ToolName("grep_search"), JsonText("""{"pattern":"x"}"""))

        gateway().stream(
            request(
                ConversationMessage.userText("look"),
                ConversationMessage.assistant(listOf(ContentBlock.Thinking("hmm", "sig"), ContentBlock.Text(""), call, call2)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call.id, call.name, "contents", false)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call2.id, call2.name, "no match", true)),
                ConversationMessage.userText("and?"),
            ),
        ) { }

        val messages = lastBody()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val assistant = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("a.txt", assistant[0]["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), results.map { it["type"]!!.jsonPrimitive.content })
        assertTrue(results[1]["is_error"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `assembles thinking with its signature and streamed tool input`() {
        server.enqueueStream(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me check"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-xyz"}}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_7","name":"read_file","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"b.txt\"}"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )

        val response = gateway().stream(request(ConversationMessage.userText("read b"))) { }

        assertEquals(
            listOf(
                ContentBlock.Thinking("Let me check", "sig-xyz"),
                ContentBlock.ToolUse(ToolCallId("toolu_7"), ToolName("read_file"), JsonText("""{"path":"b.txt"}""")),
            ),
            response.blocks,
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(TokenUsage(inputTokens = 3, outputTokens = 9), response.usage)
    }

    @Test
    fun `a missing API key fails without calling the network`() {
        val error = assertThrows<ModelProviderException> { gateway(apiKey = "").stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.NOT_CONFIGURED, error.failure)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `HTTP errors map to provider failures`() {
        server.enqueue(FakeAnthropicServer.Scripted(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        server.enqueue(FakeAnthropicServer.Scripted(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", mapOf("retry-after" to "7")))
        server.enqueue(FakeAnthropicServer.Scripted(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

        val auth = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("1"))) { } }
        val limited = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("2"))) { } }
        val overloaded = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("3"))) { } }

        assertEquals(Failure.AUTHENTICATION, auth.failure)
        assertTrue(auth.message!!.contains("invalid x-api-key"), auth.message)
        assertEquals(Failure.RATE_LIMITED, limited.failure)
        assertEquals(7, limited.retryAfterSeconds)
        assertEquals(Failure.OVERLOADED, overloaded.failure)
        assertTrue(overloaded.failure.retryable)
    }

    @Test
    fun `an error event in the middle of the stream is a provider failure`() {
        server.enqueueStream(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )

        val error = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.OVERLOADED, error.failure)
    }

    @Test
    fun `models of other providers are refused until their adaptor exists`() {
        val gpt = ModelProfile(ModelId("gpt-4.1"), ProviderKind.OPENAI_COMPATIBLE, 1024, null)

        val error =
            assertThrows<ModelProviderException> {
                gateway().stream(ModelRequest(gpt, "s", listOf(ConversationMessage.userText("hi")))) { }
            }

        assertEquals(Failure.NOT_CONFIGURED, error.failure)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, error.provider)
    }
}
