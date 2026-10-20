package com.suarvis.butler.core.domain.budget

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class BudgetTest {
    private val anthropic = ProviderId("anthropic")
    private val openai = ProviderId("openai")
    private val sonnet = FixedModelCatalog().resolve(ModelId("sonnet"))
    private val opus = FixedModelCatalog().resolve(ModelId("opus"))

    private fun record(provider: ProviderId, model: String, usd: String?, tokens: Long = 100) =
        UsageRecord(Instant.parse("2026-10-10T10:00:00Z"), provider, ModelId(model), TokenUsage(inputTokens = tokens), usd?.let(::BigDecimal))

    private val usage =
        UsageSummary.of(
            listOf(
                record(anthropic, "claude-sonnet-4-6", "4.00", tokens = 1_000),
                record(anthropic, "claude-opus-4-7", "6.00", tokens = 2_000),
                record(openai, "gpt-5.5", "1.00"),
                record(ProviderId("ollama"), "ollama/llama3.2", null, tokens = 5_000),
            ),
        )

    @Test
    fun `a budget needs a limit and limits cannot be negative`() {
        assertThrows<DomainValidationException> { Budget(BudgetScope.Provider(anthropic), null, null) }
        assertThrows<DomainValidationException> { Budget(BudgetScope.Provider(anthropic), BigDecimal("-1"), null) }
        assertThrows<DomainValidationException> { Budget(BudgetScope.Provider(anthropic), null, -5) }
    }

    @Test
    fun `usage adds up per provider and per model, unpriced usage costing nothing`() {
        assertEquals(Spend(BigDecimal("10.00"), 3_000), usage.spentOn(BudgetScope.Provider(anthropic)))
        assertEquals(Spend(BigDecimal("6.00"), 2_000), usage.spentOn(BudgetScope.Model(ModelId("claude-opus-4-7"))))
        assertEquals(Spend(BigDecimal.ZERO, 5_000), usage.spentOn(BudgetScope.Provider(ProviderId("ollama"))))
        assertEquals(4L, usage.totals.sumOf { it.calls })
    }

    @Test
    fun `a breach is reported when this month's spend plus the next request passes a limit`() {
        val providerBudget = Budget(BudgetScope.Provider(anthropic), BigDecimal("10.50"), null)
        val opusBudget = Budget(BudgetScope.Model(ModelId("claude-opus-4-7")), null, 2_500)
        val openaiBudget = Budget(BudgetScope.Provider(openai), BigDecimal("0.10"), null)
        val budgets = listOf(providerBudget, opusBudget, openaiBudget)

        val sonnetSmall = BudgetPolicy.breaches(budgets, usage, sonnet, Spend(BigDecimal("0.40"), 100))
        val sonnetLarge = BudgetPolicy.breaches(budgets, usage, sonnet, Spend(BigDecimal("0.60"), 100))
        val opusTokens = BudgetPolicy.breaches(budgets, usage, opus, Spend(BigDecimal("0.10"), 600))

        assertTrue(sonnetSmall.isEmpty(), sonnetSmall.toString())
        assertEquals(listOf(providerBudget), sonnetLarge.map { it.budget })
        assertEquals(listOf(opusBudget), opusTokens.map { it.budget })
        assertEquals("claude-opus-4-7 monthly budget: 2000 of 2500 tokens spent", opusTokens.single().describe())
    }

    @Test
    fun `a billing period is the calendar month in the user's time zone`() {
        val colombo = ZoneId.of("Asia/Colombo")
        // 20:00 UTC on 31 October is already 1 November in Colombo (UTC+5:30).
        val period = BillingPeriod.containing(Instant.parse("2026-10-31T20:00:00Z"), colombo)

        assertEquals(YearMonth.of(2026, 11), period.month)
        assertEquals(Instant.parse("2026-10-31T18:30:00Z"), period.start)
        assertEquals(Instant.parse("2026-11-30T18:30:00Z"), period.end)
        assertEquals("November 2026", period.describe())
    }

    @Test
    fun `a budget status says whether the limit is already passed`() {
        val budget = Budget(BudgetScope.Provider(anthropic), BigDecimal("10.00"), null)

        assertFalse(BudgetStatus(budget, Spend(BigDecimal("10.00"), 0)).exceeded)
        assertTrue(BudgetStatus(budget, Spend(BigDecimal("10.01"), 0)).exceeded)
    }
}
