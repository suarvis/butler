package com.suarvis.butler.core.usecase.budget

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.fixtures.FixedBudgetGateway
import com.suarvis.butler.core.fixtures.InMemoryUsageGateway
import com.suarvis.butler.core.fixtures.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

class GetUsageReportUseCaseTest {
    @Test
    fun `reports this month's usage and where each budget stands`() {
        val usage = InMemoryUsageGateway()
        val anthropic = ProviderId("anthropic")
        usage.record(UsageRecord(Instant.parse("2026-10-02T00:00:00Z"), anthropic, ModelId("claude-sonnet-4-6"), TokenUsage(inputTokens = 10), BigDecimal("12.00")))
        usage.record(UsageRecord(Instant.parse("2026-09-29T00:00:00Z"), anthropic, ModelId("claude-sonnet-4-6"), TokenUsage(inputTokens = 10), BigDecimal("99.00")))
        val budget = Budget(BudgetScope.Provider(anthropic), BigDecimal("10.00"), null)

        val report = GetUsageReportUseCase(usage, FixedBudgetGateway(listOf(budget)), MutableClock(Instant.parse("2026-10-17T09:00:00Z"))).execute()

        assertEquals(YearMonth.of(2026, 10), report.period.month)
        assertEquals(0, BigDecimal("12.00").compareTo(report.usage.overall.usd))
        assertTrue(report.budgets.single().exceeded)
    }
}
