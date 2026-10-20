package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.generic.ProviderId
import java.time.Instant

/** The usage ledger: one record per model call, across all workspaces. */
interface UsageGateway {
    fun record(record: UsageRecord)

    /** Usage recorded in [from, to), aggregated per provider and model. */
    fun summarize(from: Instant, to: Instant): UsageSummary
}

/** Remembers provider rate limits between runs, so a new CLI process does not hit a 429 again straight away. */
interface RateLimitGateway {
    /** Providers still cooling down at [now], with the instant each cooldown ends. */
    fun activeCooldowns(now: Instant): Map<ProviderId, Instant>

    fun record(provider: ProviderId, until: Instant)
}

/** The user's budgets. */
interface BudgetGateway {
    fun budgets(): List<Budget>
}
