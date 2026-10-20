package com.suarvis.butler.core.domain.budget

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ensure
import com.suarvis.butler.core.domain.model.ModelProfile
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What a budget limits: everything one provider serves, or one model. */
sealed interface BudgetScope {
    fun matches(provider: ProviderId, model: ModelId): Boolean

    fun describe(): String

    data class Provider(val provider: ProviderId) : BudgetScope {
        override fun matches(provider: ProviderId, model: ModelId): Boolean = provider == this.provider

        override fun describe(): String = provider.value
    }

    data class Model(val model: ModelId) : BudgetScope {
        override fun matches(provider: ProviderId, model: ModelId): Boolean = model == this.model

        override fun describe(): String = model.value
    }
}

/**
 * A monthly spending limit the user sets for a provider or a model, in estimated USD, in tokens,
 * or both. With your own API keys there is no prepaid balance; this is what keeps spending in check.
 */
data class Budget(
    val scope: BudgetScope,
    val monthlyUsd: BigDecimal?,
    val monthlyTokens: Long?,
) {
    init {
        ensure(monthlyUsd != null || monthlyTokens != null) { "Budget for ${scope.describe()} needs a USD or a token limit" }
        ensure(monthlyUsd == null || monthlyUsd.signum() >= 0) { "Budget for ${scope.describe()} cannot be negative" }
        ensure(monthlyTokens == null || monthlyTokens >= 0) { "Budget for ${scope.describe()} cannot be negative" }
    }

    fun appliesTo(model: ModelProfile): Boolean = scope.matches(model.provider.id, model.id)

    fun isExceededBy(spend: Spend): Boolean =
        (monthlyUsd != null && spend.usd > monthlyUsd) || (monthlyTokens != null && spend.tokens > monthlyTokens)
}

/** Estimated money and tokens. USD counts priced usage only: local and unpriced models cost nothing here. */
data class Spend(val usd: BigDecimal, val tokens: Long) {
    init {
        ensure(usd.signum() >= 0 && tokens >= 0) { "Spend cannot be negative" }
    }

    operator fun plus(other: Spend): Spend = Spend(usd + other.usd, tokens + other.tokens)

    companion object {
        val ZERO = Spend(BigDecimal.ZERO, 0)
    }
}

/** A calendar month in the user's time zone. Budgets reset when it starts. */
data class BillingPeriod(val month: YearMonth, val zone: ZoneId) {
    val start: Instant
        get() = month.atDay(1).atStartOfDay(zone).toInstant()

    /** Exclusive. */
    val end: Instant
        get() = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()

    fun describe(): String = "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"

    companion object {
        fun containing(instant: Instant, zone: ZoneId): BillingPeriod = BillingPeriod(YearMonth.from(instant.atZone(zone)), zone)
    }
}
