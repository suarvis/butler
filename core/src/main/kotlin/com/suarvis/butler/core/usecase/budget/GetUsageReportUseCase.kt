package com.suarvis.butler.core.usecase.budget

import com.suarvis.butler.core.domain.budget.BillingPeriod
import com.suarvis.butler.core.domain.budget.BudgetStatus
import com.suarvis.butler.core.domain.budget.UsageReport
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.UsageGateway
import java.time.Clock

/** This month's spend per provider and model, and where each budget stands. */
class GetUsageReportUseCase(
    private val usage: UsageGateway,
    private val budgets: BudgetGateway,
    private val clock: Clock,
) {
    fun execute(): UsageReport {
        val period = BillingPeriod.containing(clock.instant(), clock.zone)
        val summary = usage.summarize(period.start, period.end)
        return UsageReport(period, summary, budgets.budgets().map { BudgetStatus(it, summary.spentOn(it.scope)) })
    }
}
