package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.secondary.config.BudgetProperties

/** Budgets from `butler.budgets.*` configuration. */
class BudgetGatewayImpl(private val properties: BudgetProperties) : BudgetGateway {
    override fun budgets(): List<Budget> =
        properties.providers.map { (provider, limit) -> Budget(BudgetScope.Provider(ProviderId(provider)), limit.monthlyUsd, limit.monthlyTokens) } +
            properties.models.map { (model, limit) -> Budget(BudgetScope.Model(ModelId(model)), limit.monthlyUsd, limit.monthlyTokens) }
}
