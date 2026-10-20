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
import com.suarvis.butler.secondary.client.FakeProviderServer
import com.suarvis.butler.secondary.client.OpenAiResponsesStreams
import com.suarvis.butler.secondary.client.openai.OpenAiResponsesClient
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiResponsesModelGatewayImplTest {
    private val server = FakeProviderServer()
    private val openai = ProviderId("openai")
    private val gpt = ModelProfile(ModelId("gpt-6.1-sol"), ModelProvider(openai, ProviderKind.OPENAI_RESPONSES), 128_000, null)

    @AfterEach
    fun stop() = server.close()

    private fun gateway(apiKey: String = "sk-test") =
        OpenAiResponsesModelGatewayImpl(
            OpenAiResponsesClient(openai, ModelProviderProperties.Endpoint(kind = ProviderKind.OPENAI_RESPONSES, baseUrl = "${server.baseUrl}/v1", apiKey = apiKey)),
        )

    private fun request(vararg messages: ConversationMessage, model: ModelProfile = gpt) = ModelRequest(model, "You are Butler.", messages.toList())

    private fun lastBody(): JsonObject = Json.parseToJsonElement(server.requests.last().body).jsonObject

    @Test
    fun `streams text deltas and stores cached tokens apart from input`() {
        server.enqueueNamedEvents(*OpenAiResponsesStreams.textAnswer("Hel", "lo!", input = 100, outputTokens = 7, cached = 64))
        val deltas = mutableListOf<ModelStreamEvent>()

        val response = gateway().stream(request(ConversationMessage.userText("Say hello"))) { deltas += it }

        assertEquals(listOf(ModelStreamEvent.TextDelta("Hel"), ModelStreamEvent.TextDelta("lo!")), deltas)
        assertEquals(listOf(ContentBlock.Text("Hello!")), response.blocks)
        assertEquals(TokenUsage(inputTokens = 36, outputTokens = 7, cacheReadInputTokens = 64), response.usage)
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun `sends a stateless Responses request with the system prompt as the first input item`() {
        server.enqueueNamedEvents(*OpenAiResponsesStreams.textAnswer("ok"))

        gateway().stream(request(ConversationMessage.userText("hi"))) { }

        val recorded = server.requests.single()
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer sk-test", recorded.headers["authorization"])
        val body = lastBody()
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertEquals(128_000, body["max_output_tokens"]!!.jsonPrimitive.int)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), input.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("You are Butler.", input[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown output limit is left to the provider`() {
        server.enqueueNamedEvents(*OpenAiResponsesStreams.textAnswer("ok"))
        val grok = ModelProfile(ModelId("grok-4.7"), ModelProvider(ProviderId("xai"), ProviderKind.OPENAI_RESPONSES), null, null)

        gateway().stream(request(ConversationMessage.userText("hi"), model = grok)) { }

        assertNull(lastBody()["max_output_tokens"])
    }

    @Test
    fun `tool calls become function_call items and results function_call_output items`() {
        server.enqueueNamedEvents(*OpenAiResponsesStreams.textAnswer("done"))
        val call = ContentBlock.ToolUse(ToolCallId("call_1"), ToolName("read_file"), JsonText("""{"path":"a.txt"}"""))

        gateway().stream(
            request(
                ConversationMessage.userText("look"),
                ConversationMessage.assistant(listOf(ContentBlock.Text("Reading it"), call)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call.id, call.name, "no such file", true)),
                ConversationMessage.userText("and?"),
            ),
        ) { }

        val input = lastBody()["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "function_call", "function_call_output", "user"),
            input.map { (it["role"] ?: it["type"])!!.jsonPrimitive.content },
        )
        assertEquals("call_1", input[3]["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"path":"a.txt"}""", input[3]["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[4]["call_id"]!!.jsonPrimitive.content)
        assertEquals("[error] no such file", input[4]["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assembles reasoning summaries and function calls from completed output items`() {
        val reasoning = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Need the file"}]}"""
        val functionCall = """{"type":"function_call","id":"fc_1","call_id":"call_7","name":"read_file","arguments":"{\"path\":\"b.txt\"}","status":"completed"}"""
        server.enqueueNamedEvents(
            OpenAiResponsesStreams.event("response.created", """"response":{"id":"resp_1","status":"in_progress","output":[]}"""),
            OpenAiResponsesStreams.event("response.reasoning_summary_text.delta", """"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Need the file""""),
            OpenAiResponsesStreams.event("response.output_item.done", """"output_index":0,"item":$reasoning"""),
            OpenAiResponsesStreams.event("response.function_call_arguments.delta", """"item_id":"fc_1","output_index":1,"delta":"{\"path\"""""),
            OpenAiResponsesStreams.event("response.output_item.done", """"output_index":1,"item":$functionCall"""),
            // A server that sends no output in the final event still yields the items reported as done.
            OpenAiResponsesStreams.completed("[]"),
        )
        val thinking = mutableListOf<String>()

        val response = gateway().stream(request(ConversationMessage.userText("read b"))) { if (it is ModelStreamEvent.ThinkingDelta) thinking += it.text }

        assertEquals(listOf("Need the file"), thinking)
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
    fun `an answer cut off at the output limit reports max tokens`() {
        server.enqueueNamedEvents(
            OpenAiResponsesStreams.event("response.output_text.delta", """"item_id":"msg_1","output_index":0,"content_index":0,"delta":"partial""""),
            OpenAiResponsesStreams.completed("[${OpenAiResponsesStreams.message("partial")}]", status = "incomplete", incompleteReason = "max_output_tokens"),
        )

        val response = gateway().stream(request(ConversationMessage.userText("long"))) { }

        assertEquals(listOf(ContentBlock.Text("partial")), response.blocks)
        assertEquals(StopReason.MAX_TOKENS, response.stopReason)
    }

    @Test
    fun `text deltas alone still make an answer`() {
        server.enqueueNamedEvents(
            OpenAiResponsesStreams.event("response.output_text.delta", """"item_id":"msg_1","output_index":0,"content_index":0,"delta":"just """"),
            OpenAiResponsesStreams.event("response.output_text.delta", """"item_id":"msg_1","output_index":0,"content_index":0,"delta":"deltas""""),
        )

        val response = gateway().stream(request(ConversationMessage.userText("hi"))) { }

        assertEquals(listOf(ContentBlock.Text("just deltas")), response.blocks)
    }

    @Test
    fun `failed responses and error events are provider failures`() {
        server.enqueueNamedEvents(
            OpenAiResponsesStreams.event("response.failed", """"response":{"id":"resp_1","status":"failed","error":{"code":"server_error","message":"The model crashed"}}"""),
        )
        server.enqueueNamedEvents(OpenAiResponsesStreams.event("error", """"code":"rate_limit_exceeded","message":"Slow down","param":null"""))

        val failed = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("1"))) { } }
        val limited = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("2"))) { } }

        assertEquals(Failure.SERVER_ERROR, failed.failure)
        assertTrue(failed.message!!.contains("The model crashed"), failed.message)
        assertEquals(Failure.RATE_LIMITED, limited.failure)
    }

    @Test
    fun `HTTP errors and a missing key map like the other OpenAI dialect`() {
        server.enqueue(FakeProviderServer.Scripted(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"""))

        val quota = assertThrows<ModelProviderException> { gateway().stream(request(ConversationMessage.userText("1"))) { } }
        val missingKey = assertThrows<ModelProviderException> { gateway(apiKey = "").stream(request(ConversationMessage.userText("2"))) { } }

        assertEquals(Failure.QUOTA_EXHAUSTED, quota.failure)
        assertEquals(Failure.NOT_CONFIGURED, missingKey.failure)
        assertEquals(1, server.requests.size)
    }
}
