package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelProvider
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.secondary.client.AnthropicStreams
import com.suarvis.butler.secondary.client.FakeProviderServer
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnthropicModelGatewayImplTest {
    private val server = FakeProviderServer()
    private val anthropic = ProviderId("anthropic")
    private val sonnet = ModelProfile(ModelId("claude-sonnet-4-6"), ModelProvider(anthropic, ProviderKind.ANTHROPIC), 2048, null)

    @AfterEach
    fun stop() = server.close()

    private fun gateway(apiKey: String = "test-key") =
        AnthropicModelGatewayImpl(
            AnthropicMessagesClient(anthropic, ModelProviderProperties.Endpoint(kind = ProviderKind.ANTHROPIC, apiKey = apiKey, baseUrl = server.baseUrl)),
        )

    private fun request(vararg messages: ConversationMessage) = ModelRequest(sonnet, "You are Butler.", messages.toList())

    private fun lastBody(): JsonObject = Json.parseToJsonElement(server.requests.last().body).jsonObject

    @Test
    fun `streams text deltas and assembles the answer with usage`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("Hello", " there!"))
        val deltas = mutableListOf<ModelStreamEvent>()

        val response = gateway().stream(request(ConversationMessage.userText("Say hello"))) { deltas += it }

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hello"), ModelStreamEvent.TextDelta(" there!")), deltas)
        assertEquals(listOf(ContentBlock.Text("Hello there!")), response.blocks)
        assertEquals(TokenUsage(inputTokens = 12, outputTokens = 5), response.usage)
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun `sends the Messages API request with key, version and model limits`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("ok"))

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
    fun `offers tools with name, description and the schema as a JSON object`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi")).copy(tools = listOf(BuiltInTools.READ_FILE, BuiltInTools.BASH))) { }

        val body = lastBody()
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("read_file", "bash"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(setOf("name", "description", "input_schema"), tools[0].keys)
        assertEquals(BuiltInTools.READ_FILE.description, tools[0]["description"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(BuiltInTools.READ_FILE.inputSchema.value), tools[0]["input_schema"])
        assertEquals(Json.parseToJsonElement(BuiltInTools.BASH.inputSchema.value), tools[1]["input_schema"])
        assertNull(body["tool_choice"])
    }

    @Test
    fun `a request without tools has no tools field`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi"))) { }

        assertNull(lastBody()["tools"])
    }

    @Test
    fun `maps tool calls and results into Anthropic's user and assistant turns`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("done"))
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
        server.enqueueNamedEvents(
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
        server.enqueue(FakeProviderServer.Scripted(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        server.enqueue(FakeProviderServer.Scripted(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", mapOf("retry-after" to "7")))
        server.enqueue(FakeProviderServer.Scripted(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))

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
        server.enqueueNamedEvents(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )

        val error = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.OVERLOADED, error.failure)
    }

    @Test
    fun `sends the provider's model name and a default limit for models missing from the catalog`() {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("ok"))
        val referenced = ModelProfile(ModelId("anthropic/claude-next"), sonnet.provider, maxOutputTokens = null, pricing = null, apiModelName = "claude-next")

        gateway().stream(ModelRequest(referenced, "s", listOf(ConversationMessage.userText("hi")))) { }

        assertEquals("claude-next", lastBody()["model"]!!.jsonPrimitive.content)
        assertEquals(32_000, lastBody()["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an empty credit balance is reported as exhausted quota, not a bad request`() {
        server.enqueue(FakeProviderServer.Scripted(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""))

        val error = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.QUOTA_EXHAUSTED, error.failure)
        assertEquals(anthropic, error.provider)
    }
}
