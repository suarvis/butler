package com.suarvis.butler.core.domain.routing

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetBreach
import com.suarvis.butler.core.domain.budget.BudgetPolicy
import com.suarvis.butler.core.domain.budget.Spend
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import java.math.BigDecimal
import java.time.Instant

/** Why a model should not be used without asking the user first. */
sealed interface RoutingObstacle {
    data class OverBudget(val breaches: List<BudgetBreach>) : RoutingObstacle

    /** The provider answered 429; [until] comes from its retry-after or a default cooldown. */
    data class RateLimited(val provider: ProviderId, val until: Instant) : RoutingObstacle

    /** The last call failed in a way that waiting will not fix soon: no quota left, or overloaded. */
    data class Unavailable(val provider: ProviderId, val failure: ModelProviderException.Failure, val reason: String) : RoutingObstacle
}

/** The facts a routing decision is made from, gathered once per decision. */
class RoutingContext(
    val budgets: List<Budget>,
    val usage: UsageSummary,
    val cooldowns: Map<ProviderId, Instant>,
    val now: Instant,
    val estimatedInputTokens: Long,
    val isConfigured: (ProviderId) -> Boolean,
) {
    /** What the next request is expected to add: its input tokens, priced at the model's input rate. */
    fun nextSpend(model: ModelProfile): Spend =
        Spend(model.pricing?.costOf(TokenUsage(inputTokens = estimatedInputTokens)) ?: BigDecimal.ZERO, estimatedInputTokens)
}

/**
 * Which model a turn may use. The user's choice stands unless it is over budget or its provider
 * is rate limited; then the turn asks, offering the user's fallback models in their order, minus
 * those with no API key, on cooldown, over budget, or whose context window the conversation exceeds.
 */
object RoutingPolicy {
    fun obstacles(model: ModelProfile, context: RoutingContext): List<RoutingObstacle> =
        buildList {
            context.cooldowns[model.provider.id]?.takeIf { it.isAfter(context.now) }?.let { add(RoutingObstacle.RateLimited(model.provider.id, it)) }
            BudgetPolicy.breaches(context.budgets, context.usage, model, context.nextSpend(model))
                .takeIf { it.isNotEmpty() }
                ?.let { add(RoutingObstacle.OverBudget(it)) }
        }

    fun alternatives(
        current: ModelProfile,
        fallbacks: List<ModelProfile>,
        context: RoutingContext,
        avoidProviders: Set<ProviderId> = emptySet(),
    ): List<ModelProfile> =
        fallbacks
            .distinctBy { it.id }
            .filter { it.id != current.id && it.provider.id !in avoidProviders }
            .filter { context.isConfigured(it.provider.id) && fits(it, context) && obstacles(it, context).isEmpty() }

    private fun fits(model: ModelProfile, context: RoutingContext): Boolean =
        model.contextWindow == null || context.estimatedInputTokens < model.contextWindow
}

/** Rough token count, as Clawcode estimates it: about four characters per token. Good enough for budgets and context fit. */
object TokenEstimator {
    fun estimate(systemPrompt: String, messages: List<ConversationMessage>): Long {
        val characters =
            systemPrompt.length.toLong() +
                messages.sumOf { message ->
                    message.blocks.sumOf { block ->
                        when (block) {
                            is ContentBlock.Text -> block.text.length
                            is ContentBlock.Thinking -> block.thinking.length
                            is ContentBlock.ToolUse -> block.name.value.length + block.input.value.length
                            is ContentBlock.ToolResult -> block.output.length
                        }.toLong()
                    }
                }
        return (characters + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    private const val CHARS_PER_TOKEN = 4L
}
