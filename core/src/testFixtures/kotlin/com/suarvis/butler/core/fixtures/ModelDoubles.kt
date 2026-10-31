package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.model.ModelPricing
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelProvider
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import java.math.BigDecimal

/** Model gateway that replays scripted replies and records every request it receives. */
class ScriptedModelGateway(vararg replies: Reply) : ModelGateway {
    sealed interface Reply {
        data class Answer(val chunks: List<String>, val usage: TokenUsage, val stopReason: StopReason = StopReason.END_TURN) : Reply

        data class Failure(val error: ModelProviderException) : Reply

        /** A reply that calls tools: [text] first when not empty, then [calls] in order, as providers send them. */
        data class ToolCalls(
            val calls: List<ContentBlock.ToolUse>,
            val text: String,
            val usage: TokenUsage,
            val stopReason: StopReason = StopReason.TOOL_USE,
        ) : Reply
    }

    private val pending = ArrayDeque(replies.toList())
    val requests = mutableListOf<ModelRequest>()

    override fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        requests += request
        return when (val reply = pending.removeFirstOrNull() ?: error("No scripted reply left for request ${requests.size}")) {
            is Reply.Failure -> throw reply.error
            is Reply.Answer -> {
                reply.chunks.forEach { onEvent(ModelStreamEvent.TextDelta(it)) }
                ModelResponse(listOf(ContentBlock.Text(reply.chunks.joinToString(""))), reply.usage, reply.stopReason)
            }
            is Reply.ToolCalls -> {
                if (reply.text.isNotEmpty()) onEvent(ModelStreamEvent.TextDelta(reply.text))
                val text = if (reply.text.isEmpty()) emptyList() else listOf(ContentBlock.Text(reply.text))
                ModelResponse(text + reply.calls, reply.usage, reply.stopReason)
            }
        }
    }

    companion object {
        fun answer(vararg chunks: String, usage: TokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5)): Reply =
            Reply.Answer(chunks.toList(), usage)

        /** A reply with one tool call; pass [id] when a turn calls the same tool more than once. */
        fun toolCall(
            name: String,
            input: String = "{}",
            id: String = "toolu_$name",
            usage: TokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5),
        ): Reply = toolCalls(call(id, name, input), usage = usage)

        /** A reply with several tool calls, run in the order given, optionally after some text. */
        fun toolCalls(
            vararg calls: ContentBlock.ToolUse,
            text: String = "",
            usage: TokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5),
        ): Reply = Reply.ToolCalls(calls.toList(), text, usage)

        fun call(id: String, name: String, input: String = "{}"): ContentBlock.ToolUse =
            ContentBlock.ToolUse(ToolCallId(id), ToolName(name), JsonText(input))
    }
}

/**
 * Catalog with Clawcode's Anthropic aliases, `gpt-*` models served by OpenAI and `ollama/...`
 * references served by a local Ollama. Anything else is unknown. Every provider counts as
 * configured unless listed in [unconfiguredProviders].
 */
class FixedModelCatalog(
    private val default: ModelId = ModelId("sonnet"),
    var fallbackModels: List<ModelId> = listOf(ModelId("sonnet"), ModelId("gpt-5.5")),
    var unconfiguredProviders: Set<ProviderId> = emptySet(),
) : ModelCatalogGateway {
    private val aliases = mapOf("sonnet" to "claude-sonnet-4-6", "opus" to "claude-opus-4-7", "haiku" to "claude-haiku-4-5")

    override fun defaultModel(): ModelId = default

    override fun fallbacks(): List<ModelId> = fallbackModels

    override fun isConfigured(provider: ProviderId): Boolean = provider !in unconfiguredProviders

    override fun resolve(model: ModelId): ModelProfile {
        val id = aliases[model.value] ?: model.value
        return when {
            id.startsWith("claude") -> ModelProfile(ModelId(id), ANTHROPIC, maxOutputTokens = 1024, pricing = PRICING)
            id.startsWith("gpt-") -> ModelProfile(ModelId(id), OPENAI, maxOutputTokens = null, pricing = PRICING)
            id.startsWith("ollama/") -> ModelProfile(ModelId(id), OLLAMA, maxOutputTokens = null, pricing = null, apiModelName = id.removePrefix("ollama/"))
            else -> throw UnknownModelException(ModelId(id), "not in the test catalog")
        }
    }

    companion object {
        val ANTHROPIC = ModelProvider(ProviderId("anthropic"), ProviderKind.ANTHROPIC)
        val OPENAI = ModelProvider(ProviderId("openai"), ProviderKind.OPENAI_COMPATIBLE)
        val OLLAMA = ModelProvider(ProviderId("ollama"), ProviderKind.OPENAI_COMPATIBLE)
        private val PRICING = ModelPricing(BigDecimal("3"), BigDecimal("15"), BigDecimal("3.75"), BigDecimal("0.30"))
    }
}
