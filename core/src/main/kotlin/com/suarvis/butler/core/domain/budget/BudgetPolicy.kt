package com.suarvis.butler.core.domain.budget

import com.suarvis.butler.core.domain.model.ModelProfile
import java.math.RoundingMode

/** A budget the next call would take past its limit. */
data class BudgetBreach(val budget: Budget, val spent: Spend, val projected: Spend) {
    fun describe(): String {
        val limits =
            listOfNotNull(
                budget.monthlyUsd?.let { "$${spent.usd.setScale(2, RoundingMode.HALF_UP)} of $${it.setScale(2, RoundingMode.HALF_UP)}" },
                budget.monthlyTokens?.let { "${spent.tokens} of $it tokens" },
            )
        return "${budget.scope.describe()} monthly budget: ${limits.joinToString(", ")} spent"
    }
}

/**
 * Decides whether a call fits the user's budgets. The cost of an answer is only known once it
 * has been written, so the check projects this month's spend plus the estimated input of the next
 * request: a call that would start past a limit needs approval, and one answer can overshoot.
 */
object BudgetPolicy {
    fun breaches(
        budgets: List<Budget>,
        usage: UsageSummary,
        model: ModelProfile,
        next: Spend,
    ): List<BudgetBreach> =
        budgets.filter { it.appliesTo(model) }.mapNotNull { budget ->
            val spent = usage.spentOn(budget.scope)
            val projected = spent + next
            if (budget.isExceededBy(projected)) BudgetBreach(budget, spent, projected) else null
        }
}
