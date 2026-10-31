package com.suarvis.butler.secondary.client

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
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.client.openai.OpenAiChatClient
import com.suarvis.butler.secondary.client.openai.OpenAiResponsesClient
import com.suarvis.butler.secondary.config.ModelProviderProperties.Endpoint
import com.suarvis.butler.secondary.gateway.impl.AnthropicModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.OpenAiCompatibleModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.OpenAiResponsesModelGatewayImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The scripted replies the gate checks rely on (J0) are parsed by the real gateways into the same
 * blocks on every dialect, and the fake records each request in order, so a check can find the
 * tool result in the second request.
 */
class ScriptedProviderStreamsTest {
    private val server = FakeProviderServer()

    private val readNotes = ScriptedToolCall("toolu_read", "read_file", """{"path":"notes.txt"}""")

    // Clawcode's multi_tool_turn_roundtrip pieces: they cut through a key, a value and a separator.
    private val grepParity =
        ScriptedToolCall.chunked("toolu_grep", "grep_search", """{"pattern":"par""", """ity","path":"notes.txt"""", ""","output_mode":"count"}""")

    @AfterEach
    fun stop() = server.close()

    private fun gateway(dialect: ProviderDialect): ModelGateway =
        when (dialect) {
            ProviderDialect.ANTHROPIC_MESSAGES ->
                AnthropicModelGatewayImpl(AnthropicMessagesClient(PROVIDER, Endpoint(kind = ProviderKind.ANTHROPIC, baseUrl = server.baseUrl, apiKey = "test-key")))
            ProviderDialect.CHAT_COMPLETIONS ->
                OpenAiCompatibleModelGatewayImpl(OpenAiChatClient(PROVIDER, Endpoint(kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "${server.baseUrl}/v1", apiKey = "sk-test")))
            ProviderDialect.RESPONSES ->
                OpenAiResponsesModelGatewayImpl(OpenAiResponsesClient(PROVIDER, Endpoint(kind = ProviderKind.OPENAI_RESPONSES, baseUrl = "${server.baseUrl}/v1", apiKey = "sk-test")))
        }

    private fun request(dialect: ProviderDialect, vararg messages: ConversationMessage): ModelRequest {
        val (model, kind) =
            when (dialect) {
                ProviderDialect.ANTHROPIC_MESSAGES -> "claude-sonnet-4-6" to ProviderKind.ANTHROPIC
                ProviderDialect.CHAT_COMPLETIONS -> "llama3.2" to ProviderKind.OPENAI_COMPATIBLE
                ProviderDialect.RESPONSES -> "gpt-5.5" to ProviderKind.OPENAI_RESPONSES
            }
        val profile = ModelProfile(ModelId(model), ModelProvider(PROVIDER, kind), 4096, null)
        return ModelRequest(profile, "You are Butler.", messages.toList(), tools = listOf(BuiltInTools.READ_FILE, BuiltInTools.GREP_SEARCH))
    }

    private fun ScriptedToolCall.asBlock() = ContentBlock.ToolUse(ToolCallId(id), ToolName(name), JsonText(argumentsJson))

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `scripted tool calls after some text parse into the same blocks on every dialect`(dialect: ProviderDialect) {
        server.enqueue(dialect, ScriptedReply.ToolCalls(listOf(readNotes, grepParity), text = "Let me look."))
        val deltas = mutableListOf<ModelStreamEvent>()

        val response = gateway(dialect).stream(request(dialect, ConversationMessage.userText("count parity"))) { deltas += it }

        assertEquals(listOf(ContentBlock.Text("Let me look."), readNotes.asBlock(), grepParity.asBlock()), response.blocks)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(TokenUsage(inputTokens = 12, outputTokens = 5), response.usage)
        assertEquals(listOf<ModelStreamEvent>(ModelStreamEvent.TextDelta("Let me look.")), deltas)
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `a scripted two-call turn is recorded in order and the second request carries the tool result natively`(dialect: ProviderDialect) {
        server.enqueue(dialect, ScriptedReply.toolCalls(readNotes), ScriptedReply.text("The notes ", "say hello."))
        val gateway = gateway(dialect)
        val prompt = ConversationMessage.userText("read my notes")

        val first = gateway.stream(request(dialect, prompt)) { }
        val result = ContentBlock.ToolResult(ToolCallId(readNotes.id), ToolName(readNotes.name), "hello", isError = false)
        val second = gateway.stream(request(dialect, prompt, first.toAssistantMessage(), ConversationMessage.toolResult(result))) { }

        assertEquals(listOf(readNotes.asBlock()), first.blocks)
        assertEquals(listOf(ContentBlock.Text("The notes say hello.")), second.blocks)
        assertEquals(StopReason.END_TURN, second.stopReason)
        val bodies = server.requestBodies(dialect.path)
        assertEquals(2, bodies.size)
        assertEquals(listOf("read_file", "grep_search"), dialect.offeredToolNames(bodies[0]))
        assertTrue(dialect.toolResults(bodies[0]).isEmpty())
        assertEquals(listOf(readNotes.id), dialect.replayedToolCallIds(bodies[1]))
        val expectedErrorFlag = if (dialect == ProviderDialect.ANTHROPIC_MESSAGES) false else null
        assertEquals(listOf(NativeToolResult(readNotes.id, "hello", expectedErrorFlag)), dialect.toolResults(bodies[1]))
    }

    @ParameterizedTest
    @EnumSource(ProviderDialect::class)
    fun `arguments stream in pieces on every dialect`(dialect: ProviderDialect) {
        val reply = ScriptedReply.toolCalls(readNotes)

        val fragments =
            when (dialect) {
                ProviderDialect.ANTHROPIC_MESSAGES -> ScriptedStreams.anthropic(reply).count { (_, data) -> "input_json_delta" in data }
                // The first tool_calls delta opens the call with its id and name and empty arguments.
                ProviderDialect.CHAT_COMPLETIONS -> ScriptedStreams.chatCompletions(reply).count { "\"tool_calls\":[" in it } - 1
                ProviderDialect.RESPONSES -> ScriptedStreams.responses(reply).count { (name, _) -> name == "response.function_call_arguments.delta" }
            }

        assertEquals(3, fragments)
    }

    @Test
    fun `default chunks cut the arguments mid-token and join back to them`() {
        assertEquals(listOf("""{"path""", """":"note""", """s.txt"}"""), readNotes.chunks)
        assertEquals(listOf("{", "}"), ScriptedToolCall("toolu_empty", "read_file", "{}").chunks)
    }

    private companion object {
        val PROVIDER = ProviderId("fake")
    }
}
