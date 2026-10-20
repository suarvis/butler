package com.suarvis.butler.api.cli.render

import com.suarvis.butler.api.cli.dto.UsageReportOutput

/** `butler usage` as text, in the same label-and-value style as `butler status`. */
object UsageTextRenderer {
    fun report(output: UsageReportOutput): String =
        buildString {
            appendLine("Usage")
            row("Period", "${output.periodLabel} (${output.timeZone})")
            row("Total", "${output.totalCostUsd} · ${output.totalTokens} tokens")
            if (output.models.isEmpty()) appendLine("  No model calls recorded this month.")
            output.providers.forEach { provider ->
                appendLine()
                row(provider.provider, "${provider.costUsd} · ${provider.tokens} tokens")
                output.models.filter { it.provider == provider.provider }.forEach { model ->
                    appendLine("    %-30s %s · %d tokens · %d calls".format(model.model, model.costUsd, model.tokens, model.calls))
                }
            }
            appendLine()
            appendLine("Budgets")
            if (output.budgets.isEmpty()) appendLine("  None set (butler.budgets.providers.<id>.monthly-usd)")
            output.budgets.forEach { budget ->
                val limits =
                    listOfNotNull(
                        budget.monthlyUsd?.let { "${budget.spentUsd} of $it" },
                        budget.monthlyTokens?.let { "${budget.spentTokens} of $it tokens" },
                    ).joinToString(", ")
                row(budget.name, limits + if (budget.exceeded) "  EXCEEDED" else "")
            }
        }.trimEnd()

    private fun StringBuilder.row(label: String, value: Any) {
        appendLine("  %-17s %s".format(label, value))
    }
}
