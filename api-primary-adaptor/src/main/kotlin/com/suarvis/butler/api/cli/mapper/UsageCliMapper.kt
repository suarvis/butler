package com.suarvis.butler.api.cli.mapper

import com.suarvis.butler.api.cli.dto.BudgetOutput
import com.suarvis.butler.api.cli.dto.UsageModelOutput
import com.suarvis.butler.api.cli.dto.UsageProviderOutput
import com.suarvis.butler.api.cli.dto.UsageReportOutput
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.Spend
import com.suarvis.butler.core.domain.budget.UsageReport
import com.suarvis.butler.core.domain.budget.UsageTotal
import java.math.BigDecimal
import java.math.RoundingMode

/** Domain usage report → CLI output. Money keeps Clawcode's `$0.0000` format. */
object UsageCliMapper {
    fun toOutput(report: UsageReport): UsageReportOutput {
        val totals = report.usage.totals.sortedWith(compareBy<UsageTotal>({ it.provider.value }, { it.model.value }))
        return UsageReportOutput(
            period = report.period.month.toString(),
            periodLabel = report.period.describe(),
            timeZone = report.period.zone.id,
            totalCostUsd = TurnCliMapper.formatUsd(report.usage.overall.usd),
            totalTokens = report.usage.overall.tokens,
            providers =
                totals.groupBy { it.provider }.map { (provider, group) ->
                    val spend = group.fold(Spend.ZERO) { sum, total -> sum + total.spend }
                    UsageProviderOutput(provider.value, TurnCliMapper.formatUsd(spend.usd), spend.tokens)
                },
            models = totals.map { UsageModelOutput(it.provider.value, it.model.value, it.calls, TurnCliMapper.formatUsd(it.spend.usd), it.spend.tokens) },
            budgets =
                report.budgets.map { status ->
                    val scope = status.budget.scope
                    BudgetOutput(
                        scope = if (scope is BudgetScope.Provider) "provider" else "model",
                        name = scope.describe(),
                        monthlyUsd = status.budget.monthlyUsd?.let(::dollars),
                        monthlyTokens = status.budget.monthlyTokens,
                        spentUsd = TurnCliMapper.formatUsd(status.spent.usd),
                        spentTokens = status.spent.tokens,
                        exceeded = status.exceeded,
                    )
                },
        )
    }

    private fun dollars(amount: BigDecimal): String = "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
