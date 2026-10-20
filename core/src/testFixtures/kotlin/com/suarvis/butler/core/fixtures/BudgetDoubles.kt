package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.UsageGateway
import java.time.Instant

class InMemoryUsageGateway : UsageGateway {
    val records = mutableListOf<UsageRecord>()

    override fun record(record: UsageRecord) {
        records += record
    }

    override fun summarize(from: Instant, to: Instant): UsageSummary =
        UsageSummary.of(records.filter { !it.at.isBefore(from) && it.at.isBefore(to) })
}

class InMemoryRateLimitGateway : RateLimitGateway {
    val cooldowns = mutableMapOf<ProviderId, Instant>()

    override fun activeCooldowns(now: Instant): Map<ProviderId, Instant> = cooldowns.filterValues { it.isAfter(now) }

    override fun record(provider: ProviderId, until: Instant) {
        cooldowns[provider] = until
    }
}

class FixedBudgetGateway(var budgets: List<Budget> = emptyList()) : BudgetGateway {
    override fun budgets(): List<Budget> = budgets
}
