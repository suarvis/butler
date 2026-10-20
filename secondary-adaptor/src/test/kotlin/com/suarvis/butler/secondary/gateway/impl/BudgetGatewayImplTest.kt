package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.config.BudgetProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class BudgetGatewayImplTest {
    @Test
    fun `provider and model limits become budgets`() {
        val properties =
            BudgetProperties(
                providers = mapOf("anthropic" to BudgetProperties.Limit(monthlyUsd = BigDecimal("50"), monthlyTokens = 20_000_000)),
                models = mapOf("claude-opus-4-7" to BudgetProperties.Limit(monthlyUsd = BigDecimal("20"))),
            )

        assertEquals(
            listOf(
                Budget(BudgetScope.Provider(ProviderId("anthropic")), BigDecimal("50"), 20_000_000),
                Budget(BudgetScope.Model(ModelId("claude-opus-4-7")), BigDecimal("20"), null),
            ),
            BudgetGatewayImpl(properties).budgets(),
        )
    }

    @Test
    fun `an entry without any limit is a configuration error`() {
        val properties = BudgetProperties(providers = mapOf("openai" to BudgetProperties.Limit()))

        assertThrows<DomainValidationException> { BudgetGatewayImpl(properties).budgets() }
    }
}
