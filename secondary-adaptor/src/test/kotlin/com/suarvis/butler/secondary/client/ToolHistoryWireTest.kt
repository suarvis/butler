package com.suarvis.butler.secondary.client

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelProvider
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.TranscriptNormalizer
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.client.openai.OpenAiChatClient
import com.suarvis.butler.secondary.client.openai.OpenAiResponsesClient
import com.suarvis.butler.secondary.config.ModelProviderProperties
import com.suarvis.butler.secondary.gateway.impl.AnthropicModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.OpenAiCompatibleModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.OpenAiResponsesModelGatewayImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B4: native tool history on all three dialects, sent the way lane A will send it (the transcript
 * normalized against the tools offered in the same request, then the gateway) and checked on the
 * body each fake server received. The fakes accept anything, so the expected JSON spells out each
 * provider's documented shape.
 */
class ToolHistoryWireTest {
    private val server = FakeProviderServer()
    private val anthropic = ProviderId("anthropic")
    private val openai = ProviderId("openai")
    private val claude = ModelProfile(ModelId("claude-sonnet-4-6"), ModelProvider(anthropic, ProviderKind.ANTHROPIC), 2048, null)
    private val chatGpt = ModelProfile(ModelId("gpt-5.5"), ModelProvider(openai, ProviderKind.OPENAI_COMPATIBLE), 4096, null)
    private val responsesGpt = ModelProfile(ModelId("gpt-6.1-sol"), ModelProvider(openai, ProviderKind.OPENAI_RESPONSES), 128_000, null)

    private val readA = ContentBlock.ToolUse(ToolCallId(LONG_ID), BuiltInTools.READ_FILE.name, JsonText("""{"path":"a.txt"}"""))
    private val readMissing = ContentBlock.ToolUse(ToolCallId("toolu_02"), BuiltInTools.READ_FILE.name, JsonText("""{"path":"missing.txt"}"""))

    /** A prompt, one answer with two read_file calls, a result and an error result, then a follow-up prompt. */
    private val readTwoFiles =
        listOf(
            ConversationMessage.userText("What do a.txt and missing.txt say?"),
            ConversationMessage.assistant(listOf(ContentBlock.Thinking("Read both", "sig-1"), readA, readMissing)),
            ConversationMessage.toolResult(ContentBlock.ToolResult(readA.id, readA.name, "hello", isError = false)),
            ConversationMessage.toolResult(ContentBlock.ToolResult(readMissing.id, readMissing.name, "No such file", isError = true)),
            ConversationMessage.userText("Thanks."),
        )

    /** The ids the normalizer gives the two calls; every dialect must carry exactly these. */
    private val ids = TranscriptNormalizer.normalize(readTwoFiles, OFFERED.map { it.name }.toSet()).flatMap { it.toolUses }.map { it.id.value }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the scenario's long Anthropic id becomes portable and the short one is kept`() {
        assertNotEquals(LONG_ID, ids[0])
        assertTrue(PORTABLE_ID.matches(ids[0]), ids[0])
        assertEquals("toolu_02", ids[1])
    }

    @Test
    fun `Anthropic writes tool_use blocks in the assistant turn and tool_result blocks, is_error only when true, at the start of the next user turn`() {
        val body = parse(sendAnthropic(readTwoFiles)).jsonObject

        assertEquals(listOf("read_file"), body.objects("tools").map { it.string("name") })
        assertEquals(
            parse(
                """
                [
                  {"role":"user","content":[{"type":"text","text":"What do a.txt and missing.txt say?"}]},
                  {"role":"assistant","content":[
                    {"type":"tool_use","id":"${ids[0]}","name":"read_file","input":{"path":"a.txt"}},
                    {"type":"tool_use","id":"toolu_02","name":"read_file","input":{"path":"missing.txt"}}
                  ]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"${ids[0]}","content":"hello"},
                    {"type":"tool_result","tool_use_id":"toolu_02","content":"No such file","is_error":true},
                    {"type":"text","text":"Thanks."}
                  ]}
                ]
                """,
            ),
            body.getValue("messages"),
        )
    }

    @Test
    fun `Chat Completions puts tool_calls on the assistant message with null content and sends one tool message per result`() {
        val body = parse(sendChat(readTwoFiles)).jsonObject

        assertEquals(listOf("read_file"), body.objects("tools").map { it.getValue("function").jsonObject.string("name") })
        assertEquals(
            parse(
                """
                [
                  {"role":"system","content":"You are Butler."},
                  {"role":"user","content":"What do a.txt and missing.txt say?"},
                  {"role":"assistant","content":null,"tool_calls":[
                    {"id":"${ids[0]}","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}},
                    {"id":"toolu_02","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"missing.txt\"}"}}
                  ]},
                  {"role":"tool","tool_call_id":"${ids[0]}","content":"hello"},
                  {"role":"tool","tool_call_id":"toolu_02","content":"[error] No such file"},
                  {"role":"user","content":"Thanks."}
                ]
                """,
            ),
            body.getValue("messages"),
        )
    }

    @Test
    fun `Responses writes function_call items and one function_call_output item per result`() {
        val body = parse(sendResponses(readTwoFiles)).jsonObject

        assertEquals(listOf("read_file"), body.objects("tools").map { it.string("name") })
        assertEquals(
            parse(
                """
                [
                  {"role":"system","content":"You are Butler."},
                  {"role":"user","content":"What do a.txt and missing.txt say?"},
                  {"type":"function_call","call_id":"${ids[0]}","name":"read_file","arguments":"{\"path\":\"a.txt\"}"},
                  {"type":"function_call","call_id":"toolu_02","name":"read_file","arguments":"{\"path\":\"missing.txt\"}"},
                  {"type":"function_call_output","call_id":"${ids[0]}","output":"hello"},
                  {"type":"function_call_output","call_id":"toolu_02","output":"[error] No such file"},
                  {"role":"user","content":"Thanks."}
                ]
                """,
            ),
            body.getValue("input"),
        )
    }

    @Test
    fun `text the assistant wrote beside its call stays with it, and a transcript may end on the results`() {
        val call = ContentBlock.ToolUse(ToolCallId("toolu_04"), BuiltInTools.READ_FILE.name, JsonText("""{"path":"a.txt"}"""))
        val transcript =
            listOf(
                ConversationMessage.userText("Read a.txt"),
                ConversationMessage.assistant(listOf(ContentBlock.Text("Reading it."), call)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(call.id, call.name, "hello", isError = false)),
            )

        assertEquals(
            parse(
                """
                [
                  {"role":"user","content":[{"type":"text","text":"Read a.txt"}]},
                  {"role":"assistant","content":[
                    {"type":"text","text":"Reading it."},
                    {"type":"tool_use","id":"toolu_04","name":"read_file","input":{"path":"a.txt"}}
                  ]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_04","content":"hello"}]}
                ]
                """,
            ),
            parse(sendAnthropic(transcript)).jsonObject.getValue("messages"),
        )
        assertEquals(
            parse(
                """
                [
                  {"role":"system","content":"You are Butler."},
                  {"role":"user","content":"Read a.txt"},
                  {"role":"assistant","content":"Reading it.","tool_calls":[
                    {"id":"toolu_04","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}
                  ]},
                  {"role":"tool","tool_call_id":"toolu_04","content":"hello"}
                ]
                """,
            ),
            parse(sendChat(transcript)).jsonObject.getValue("messages"),
        )
        assertEquals(
            parse(
                """
                [
                  {"role":"system","content":"You are Butler."},
                  {"role":"user","content":"Read a.txt"},
                  {"role":"assistant","content":"Reading it."},
                  {"type":"function_call","call_id":"toolu_04","name":"read_file","arguments":"{\"path\":\"a.txt\"}"},
                  {"type":"function_call_output","call_id":"toolu_04","output":"hello"}
                ]
                """,
            ),
            parse(sendResponses(transcript)).jsonObject.getValue("input"),
        )
    }

    @Test
    fun `a call to a tool no longer offered reaches every dialect as text, never as a native call`() {
        val listFiles = ContentBlock.ToolUse(ToolCallId("toolu_bash1"), BuiltInTools.BASH.name, JsonText("""{"command":"ls"}"""))
        val transcript =
            listOf(
                ConversationMessage.userText("List the files"),
                ConversationMessage.assistant(listOf(ContentBlock.Text("Listing them."), listFiles)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(listFiles.id, listFiles.name, "a.txt", isError = false)),
                ConversationMessage.userText("Read a.txt"),
            )

        assertEquals(
            parse(
                """
                [
                  {"role":"user","content":[{"type":"text","text":"List the files"}]},
                  {"role":"assistant","content":[
                    {"type":"text","text":"Listing them."},
                    {"type":"text","text":"[tool call bash (toolu_bash1)] {\"command\":\"ls\"}"}
                  ]},
                  {"role":"user","content":[
                    {"type":"text","text":"[tool result bash (toolu_bash1)] a.txt"},
                    {"type":"text","text":"Read a.txt"}
                  ]}
                ]
                """,
            ),
            parse(sendAnthropic(transcript)).jsonObject.getValue("messages"),
        )
        // Both OpenAI dialects send the same plain messages.
        val plain =
            parse(
                """
                [
                  {"role":"system","content":"You are Butler."},
                  {"role":"user","content":"List the files"},
                  {"role":"assistant","content":"Listing them.\n[tool call bash (toolu_bash1)] {\"command\":\"ls\"}"},
                  {"role":"user","content":"[tool result bash (toolu_bash1)] a.txt\nRead a.txt"}
                ]
                """,
            )
        assertEquals(plain, parse(sendChat(transcript)).jsonObject.getValue("messages"))
        assertEquals(plain, parse(sendResponses(transcript)).jsonObject.getValue("input"))
    }

    @Test
    fun `tool input that is not a JSON object is sent as an empty object on every dialect`() {
        val cutOff = ContentBlock.ToolUse(ToolCallId("toolu_03"), BuiltInTools.READ_FILE.name, JsonText("""{"path":"""))
        val transcript =
            listOf(
                ConversationMessage.userText("Read a.txt"),
                ConversationMessage.assistant(listOf(cutOff)),
                ConversationMessage.toolResult(ContentBlock.ToolResult(cutOff.id, cutOff.name, "invalid input", isError = true)),
            )

        val anthropicCall = parse(sendAnthropic(transcript)).jsonObject.objects("messages")[1].objects("content").single()
        val chatCall = parse(sendChat(transcript)).jsonObject.objects("messages")[2].objects("tool_calls").single()
        val responsesCall = parse(sendResponses(transcript)).jsonObject.objects("input").single { it["type"]?.jsonPrimitive?.content == "function_call" }

        assertEquals(JsonObject(emptyMap()), anthropicCall.getValue("input"))
        assertEquals("{}", chatCall.getValue("function").jsonObject.string("arguments"))
        assertEquals("{}", responsesCall.string("arguments"))
    }

    @Test
    fun `transcripts without tool history are sent byte for byte as before`() {
        val transcript =
            listOf(
                ConversationMessage.userText("hi"),
                ConversationMessage.assistant(listOf(ContentBlock.Text("Hello!"))),
                ConversationMessage.userText("and?"),
            )

        assertEquals(
            """{"model":"claude-sonnet-4-6","max_tokens":2048,"stream":true,"system":"You are Butler.","messages":[""" +
                """{"role":"user","content":[{"type":"text","text":"hi"}]},{"role":"assistant","content":[{"type":"text","text":"Hello!"}]},""" +
                """{"role":"user","content":[{"type":"text","text":"and?"}]}]}""",
            sendAnthropic(transcript, tools = emptyList()),
        )
        assertEquals(
            """{"model":"gpt-5.5","stream":true,"stream_options":{"include_usage":true},"max_tokens":4096,"messages":[""" +
                """{"role":"system","content":"You are Butler."},{"role":"user","content":"hi"},{"role":"assistant","content":"Hello!"},""" +
                """{"role":"user","content":"and?"}]}""",
            sendChat(transcript, tools = emptyList()),
        )
        assertEquals(
            """{"model":"gpt-6.1-sol","stream":true,"store":false,"max_output_tokens":128000,"input":[""" +
                """{"role":"system","content":"You are Butler."},{"role":"user","content":"hi"},{"role":"assistant","content":"Hello!"},""" +
                """{"role":"user","content":"and?"}]}""",
            sendResponses(transcript, tools = emptyList()),
        )
    }

    @Test
    fun `a Chat Completions answer with whole tool calls and no index, as some local servers send, yields each call`() {
        server.enqueueDataEvents(
            OpenAiStreams.chunk(
                """{"role":"assistant","tool_calls":[""" +
                    """{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}},""" +
                    """{"id":"call_b","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"b.txt\"}"}}]}""",
            ),
            OpenAiStreams.chunk("{}", finishReason = "tool_calls"),
            OpenAiStreams.usage(prompt = 30, completion = 12),
            "[DONE]",
        )

        val response = chatGateway().stream(request(chatGpt, listOf(ConversationMessage.userText("Read a.txt and b.txt")), OFFERED)) { }

        assertEquals(
            listOf(
                ContentBlock.ToolUse(ToolCallId("call_a"), BuiltInTools.READ_FILE.name, JsonText("""{"path":"a.txt"}""")),
                ContentBlock.ToolUse(ToolCallId("call_b"), BuiltInTools.READ_FILE.name, JsonText("""{"path":"b.txt"}""")),
            ),
            response.blocks,
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
    }

    /** As lane A builds it: the transcript normalized against the tools offered in the same request. */
    private fun request(model: ModelProfile, transcript: List<ConversationMessage>, tools: List<ToolDefinition>): ModelRequest =
        ModelRequest(model, SYSTEM, TranscriptNormalizer.normalize(transcript, tools.map { it.name }.toSet()), tools)

    /** Each `send` returns the request body exactly as the fake server received it. */
    private fun sendAnthropic(transcript: List<ConversationMessage>, tools: List<ToolDefinition> = OFFERED): String {
        server.enqueueNamedEvents(*AnthropicStreams.textAnswer("ok"))
        anthropicGateway().stream(request(claude, transcript, tools)) { }
        return server.requests.last().body
    }

    private fun sendChat(transcript: List<ConversationMessage>, tools: List<ToolDefinition> = OFFERED): String {
        server.enqueueDataEvents(*OpenAiStreams.textAnswer("ok"))
        chatGateway().stream(request(chatGpt, transcript, tools)) { }
        return server.requests.last().body
    }

    private fun sendResponses(transcript: List<ConversationMessage>, tools: List<ToolDefinition> = OFFERED): String {
        server.enqueueNamedEvents(*OpenAiResponsesStreams.textAnswer("ok"))
        responsesGateway().stream(request(responsesGpt, transcript, tools)) { }
        return server.requests.last().body
    }

    private fun anthropicGateway() =
        AnthropicModelGatewayImpl(
            AnthropicMessagesClient(anthropic, ModelProviderProperties.Endpoint(kind = ProviderKind.ANTHROPIC, apiKey = "test-key", baseUrl = server.baseUrl)),
        )

    private fun chatGateway() =
        OpenAiCompatibleModelGatewayImpl(OpenAiChatClient(openai, ModelProviderProperties.Endpoint(baseUrl = "${server.baseUrl}/v1", apiKey = "sk-test")))

    private fun responsesGateway() =
        OpenAiResponsesModelGatewayImpl(
            OpenAiResponsesClient(openai, ModelProviderProperties.Endpoint(kind = ProviderKind.OPENAI_RESPONSES, baseUrl = "${server.baseUrl}/v1", apiKey = "sk-test")),
        )

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val SYSTEM = "You are Butler."

        /** Anthropic's characters but longer than OpenAI's 40, so the normalizer must replace it. */
        const val LONG_ID = "toolu_01ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij"

        val OFFERED = listOf(BuiltInTools.READ_FILE)
        val PORTABLE_ID = Regex("[A-Za-z0-9_-]{1,40}")
    }
}
