package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.model.ModelPricing
import com.suarvis.butler.core.domain.model.ModelProfile
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
        }
    }

    companion object {
        fun answer(vararg chunks: String, usage: TokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5)): Reply =
            Reply.Answer(chunks.toList(), usage)
    }
}

/** Catalog with Clawcode's Anthropic aliases and simple pricing. */
class FixedModelCatalog(private val default: ModelId = ModelId("sonnet")) : ModelCatalogGateway {
    private val aliases = mapOf("sonnet" to "claude-sonnet-4-6", "opus" to "claude-opus-4-7", "haiku" to "claude-haiku-4-5")

    override fun defaultModel(): ModelId = default

    override fun resolve(model: ModelId): ModelProfile =
        ModelProfile(
            id = ModelId(aliases[model.value] ?: model.value),
            provider = ProviderKind.ANTHROPIC,
            maxOutputTokens = 1024,
            pricing = ModelPricing(BigDecimal("3"), BigDecimal("15"), BigDecimal("3.75"), BigDecimal("0.30")),
        )
}
