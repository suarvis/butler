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
import com.suarvis.butler.secondary.client.FakeProviderServer
import com.suarvis.butler.secondary.client.OpenAiStreams
import com.suarvis.butler.secondary.client.openai.OpenAiChatClient
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiCompatibleModelGatewayImplTest {
    private val server = FakeProviderServer()
    private val openai = ProviderId("openai")
    private val gpt = ModelProfile(ModelId("gpt-5.5"), ModelProvider(openai, ProviderKind.OPENAI_COMPATIBLE), 4096, null)

    @AfterEach
    fun stop() = server.close()

    private fun gateway(
        apiKey: String = "sk-test",
        apiKeyRequired: Boolean = true,
        maxTokensParameter: String = "max_completion_tokens",
        streamUsage: Boolean = true,
    ) = OpenAiCompatibleModelGatewayImpl(
        OpenAiChatClient(
            openai,
            ModelProviderProperties.Endpoint(
                baseUrl = "${server.baseUrl}/v1",
                apiKey = apiKey,
                apiKeyRequired = apiKeyRequired,
                maxTokensParameter = maxTokensParameter,
                streamUsage = streamUsage,
            ),
        ),
    )

    private fun request(vararg messages: ConversationMessage, model: ModelProfile = gpt) = ModelRequest(model, "You are Butler.", messages.toList())

    private fun lastBody(): JsonObject = Json.parseToJsonElement(server.requests.last().body).jsonObject

    @Test
    fun `streams text deltas and stores cached tokens apart from input`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("Hel", "lo!", prompt = 100, completion = 7, cached = 60))
        val deltas = mutableListOf<ModelStreamEvent>()

        val response = gateway().stream(request(ConversationMessage.userText("Say hello"))) { deltas += it }

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hel"), ModelStreamEvent.TextDelta("lo!")), deltas)
        assertEquals(listOf(ContentBlock.Text("Hello!")), response.blocks)
        assertEquals(TokenUsage(inputTokens = 40, outputTokens = 7, cacheReadInputTokens = 60), response.usage)
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun `sends Chat Completions with a bearer key, the system prompt first and usage requested`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi"))) { }

        val recorded = server.requests.single()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.headers["authorization"])
        val body = lastBody()
        assertEquals("gpt-5.5", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals(4096, body["max_completion_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("You are Butler.", messages[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `server differences are configuration - token parameter, usage option and unknown limits`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("a")).enqueueDataEvents(*OpenAiStreams.textAnswer("b"))
        val local = ModelProfile(ModelId("ollama/llama3.2"), ModelProvider(ProviderId("ollama"), ProviderKind.OPENAI_COMPATIBLE), null, null, "llama3.2")

        gateway(maxTokensParameter = "max_tokens", streamUsage = false).stream(request(ConversationMessage.userText("1"))) { }
        val first = lastBody()
        gateway().stream(request(ConversationMessage.userText("2"), model = local)) { }
        val second = lastBody()

        assertEquals(4096, first["max_tokens"]!!.jsonPrimitive.int)
        assertNull(first["stream_options"])
        assertEquals("llama3.2", second["model"]!!.jsonPrimitive.content)
        assertFalse(second.keys.any { it.startsWith("max_") }, second.toString())
    }

    @Test
    fun `offers tools as functions with the schema as a JSON object`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi")).copy(tools = listOf(BuiltInTools.READ_FILE, BuiltInTools.BASH))) { }

        val body = lastBody()
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("function", "function"), tools.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(setOf("type", "function"), tools[0].keys)
        val functions = tools.map { it["function"]!!.jsonObject }
        assertEquals(listOf("read_file", "bash"), functions.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(setOf("name", "description", "parameters"), functions[0].keys)
        assertEquals(BuiltInTools.BASH.description, functions[1]["description"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(BuiltInTools.READ_FILE.inputSchema.value), functions[0]["parameters"])
        assertEquals(Json.parseToJsonElement(BuiltInTools.BASH.inputSchema.value), functions[1]["parameters"])
        assertNull(body["tool_choice"])
        // The server settings still apply next to tools.
        assertEquals(4096, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a request without tools has no tools field`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi"))) { }

        assertNull(lastBody()["tools"])
    }

    @Test
    fun `tool calls ride on the assistant message and each result is its own tool message`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("done"))
        val call = ContentBlock.ToolUse(ToolCallId("call_1"), ToolName("read_file"), JsonText("""{"path":"a.txt"}"""))
        val call2 = ContentBlock.ToolUse(ToolCallId("call_2"), ToolName("grep_search"), JsonText("""{"pattern":"x"}"""))

        gateway().stream(
            request(
                ConversationMessage.userText("look"),
                ConversationMessage.assistant(listOf(call, call2)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call.id, call.name, "contents", false)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call2.id, call2.name, "no match", true)),
                ConversationMessage.userText("and?"),
            ),
        ) { }

        val messages = lastBody()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val assistant = messages[2]
        assertEquals(JsonNull, assistant["content"])
        val calls = assistant["tool_calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("call_1", "call_2"), calls.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("""{"path":"a.txt"}""", calls[0]["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_2", messages[4]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("[error] no match", messages[4]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assembles reasoning and tool calls streamed in fragments`() {
        server.enqueueDataEvents(
            OpenAiStreams.chunk("""{"role":"assistant","reasoning_content":"Need the "}"""),
            OpenAiStreams.chunk("""{"reasoning_content":"file"}"""),
            OpenAiStreams.chunk("""{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"read_file","arguments":""}}]}"""),
            OpenAiStreams.chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}"""),
            OpenAiStreams.chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"\"b.txt\"}"}}]}"""),
            OpenAiStreams.chunk("{}", finishReason = "tool_calls"),
            OpenAiStreams.usage(prompt = 30, completion = 12),
            "[DONE]",
        )
        val thinking = mutableListOf<String>()

        val response = gateway().stream(request(ConversationMessage.userText("read b"))) { if (it is ModelStreamEvent.ThinkingDelta) thinking += it.text }

        assertEquals(listOf("Need the ", "file"), thinking)
        assertEquals(
            listOf(
                ContentBlock.Thinking("Need the file", signature = null),
                ContentBlock.ToolUse(ToolCallId("call_7"), ToolName("read_file"), JsonText("""{"path":"b.txt"}""")),
            ),
            response.blocks,
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
    }

    @Test
    fun `a required key that is missing fails without calling the network`() {
        val error = assertThrows<ModelProviderException> { gateway(apiKey = "").stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.NOT_CONFIGURED, error.failure)
        assertEquals(openai, error.provider)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `local servers can run without a key and get no authorization header`() {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("local"))

        gateway(apiKey = "", apiKeyRequired = false).stream(request(ConversationMessage.userText("hi"))) { }

        assertNull(server.requests.single().headers["authorization"])
    }

    @Test
    fun `HTTP errors map to provider failures, telling exhausted quota from rate limits`() {
        server.enqueue(FakeProviderServer.Scripted(401, """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""))
        server.enqueue(FakeProviderServer.Scripted(429, """{"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}""", mapOf("retry-after" to "3")))
        server.enqueue(FakeProviderServer.Scripted(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"""))
        server.enqueue(FakeProviderServer.Scripted(404, """{"error":"model 'llama9' not found"}"""))

        val failures = (1..4).map { assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("$it"))) { } } }

        assertEquals(listOf(Failure.AUTHENTICATION, Failure.RATE_LIMITED, Failure.QUOTA_EXHAUSTED, Failure.INVALID_REQUEST), failures.map { it.failure })
        assertEquals(3, failures[1].retryAfterSeconds)
        assertFalse(failures[2].failure.retryable)
        assertTrue(failures[3].message!!.contains("model 'llama9' not found"), failures[3].message)
    }

    @Test
    fun `an error object in the middle of the stream is a provider failure`() {
        server.enqueueDataEvents(
            OpenAiStreams.chunk("""{"content":"par"}"""),
            """{"error":{"message":"upstream overloaded","code":"server_error"}}""",
        )

        val error = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("hi"))) { } }

        assertEquals(Failure.SERVER_ERROR, error.failure)
        assertTrue(error.message!!.contains("upstream overloaded"), error.message)
    }
}
