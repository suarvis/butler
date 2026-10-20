package com.suarvis.butler.core.domain.budget

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ensure
import java.math.BigDecimal
import java.time.Instant

/**
 * One model call in the usage ledger. Kept apart from sessions because budgets span every
 * workspace, and sessions do not say which provider produced each answer.
 */
data class UsageRecord(
    val at: Instant,
    val provider: ProviderId,
    val model: ModelId,
    val usage: TokenUsage,
    val costUsd: BigDecimal?,
) {
    init {
        ensure(costUsd == null || costUsd.signum() >= 0) { "Cost cannot be negative" }
    }
}

/** Everything spent on one model of one provider in a period. */
data class UsageTotal(
    val provider: ProviderId,
    val model: ModelId,
    val calls: Long,
    val spend: Spend,
)

/** The ledger aggregated over a period, one entry per provider and model. */
data class UsageSummary(val totals: List<UsageTotal>) {
    fun spentOn(scope: BudgetScope): Spend =
        totals.filter { scope.matches(it.provider, it.model) }.fold(Spend.ZERO) { sum, total -> sum + total.spend }

    val overall: Spend
        get() = totals.fold(Spend.ZERO) { sum, total -> sum + total.spend }

    companion object {
        val EMPTY = UsageSummary(emptyList())

        /** Aggregates raw records; stores that cannot aggregate themselves (files) use this. */
        fun of(records: List<UsageRecord>): UsageSummary =
            UsageSummary(
                records.groupBy { it.provider to it.model }.map { (key, group) ->
                    UsageTotal(
                        provider = key.first,
                        model = key.second,
                        calls = group.size.toLong(),
                        spend =
                            group.fold(Spend.ZERO) { sum, record ->
                                sum + Spend(record.costUsd ?: BigDecimal.ZERO, record.usage.totalTokens)
                            },
                    )
                },
            )
    }
}

/** A budget as it stands this period. */
data class BudgetStatus(val budget: Budget, val spent: Spend) {
    val exceeded: Boolean
        get() = budget.isExceededBy(spent)
}

/** This month's usage and budgets, for `butler usage`. */
data class UsageReport(
    val period: BillingPeriod,
    val usage: UsageSummary,
    val budgets: List<BudgetStatus>,
)
