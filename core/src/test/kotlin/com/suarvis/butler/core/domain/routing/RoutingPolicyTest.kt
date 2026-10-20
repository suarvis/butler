package com.suarvis.butler.core.domain.routing

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class RoutingPolicyTest {
    private val catalog = FixedModelCatalog()
    private val sonnet = catalog.resolve(ModelId("sonnet"))
    private val gpt = catalog.resolve(ModelId("gpt-5.5"))
    private val local = catalog.resolve(ModelId("ollama/llama3.2"))
    private val now = Instant.parse("2026-10-17T09:00:00Z")

    private fun context(
        budgets: List<Budget> = emptyList(),
        cooldowns: Map<ProviderId, Instant> = emptyMap(),
        estimatedInputTokens: Long = 1_000,
        unconfigured: Set<ProviderId> = emptySet(),
    ) = RoutingContext(budgets, UsageSummary.EMPTY, cooldowns, now, estimatedInputTokens) { it !in unconfigured }

    @Test
    fun `a clear model has no obstacles`() {
        assertTrue(RoutingPolicy.obstacles(sonnet, context()).isEmpty())
    }

    @Test
    fun `an active cooldown and a breached budget are both reported`() {
        val budget = Budget(BudgetScope.Provider(ProviderId("anthropic")), null, 500)
        val context = context(budgets = listOf(budget), cooldowns = mapOf(ProviderId("anthropic") to now.plusSeconds(10)))

        val obstacles = RoutingPolicy.obstacles(sonnet, context)

        assertEquals(RoutingObstacle.RateLimited(ProviderId("anthropic"), now.plusSeconds(10)), obstacles[0])
        assertTrue(obstacles[1] is RoutingObstacle.OverBudget)
    }

    @Test
    fun `an expired cooldown is no obstacle`() {
        assertTrue(RoutingPolicy.obstacles(sonnet, context(cooldowns = mapOf(ProviderId("anthropic") to now.minusSeconds(1)))).isEmpty())
    }

    @Test
    fun `alternatives keep the user's order and drop the current model, avoided and unconfigured providers`() {
        val fallbacks = listOf(local, sonnet, gpt, gpt)

        assertEquals(listOf(local, gpt), RoutingPolicy.alternatives(sonnet, fallbacks, context()))
        assertEquals(listOf(gpt), RoutingPolicy.alternatives(sonnet, fallbacks, context(), avoidProviders = setOf(ProviderId("ollama"))))
        assertEquals(listOf(local), RoutingPolicy.alternatives(sonnet, fallbacks, context(unconfigured = setOf(ProviderId("openai")))))
    }

    @Test
    fun `a model whose context window the conversation exceeds is not offered`() {
        val small = gpt.copy(contextWindow = 8_000)

        assertEquals(listOf(local), RoutingPolicy.alternatives(sonnet, listOf(small, local), context(estimatedInputTokens = 10_000)))
    }

    @Test
    fun `the next request is priced at the model's input rate`() {
        val next = context(estimatedInputTokens = 1_000_000).nextSpend(sonnet)

        assertEquals(0, BigDecimal("3").compareTo(next.usd))
        assertEquals(1_000_000, next.tokens)
        assertEquals(0, BigDecimal.ZERO.compareTo(context().nextSpend(local).usd))
    }

    @Test
    fun `tokens are estimated at about four characters each`() {
        assertEquals(3, TokenEstimator.estimate("12345678", listOf(ConversationMessage.userText("abc"))))
    }
}
