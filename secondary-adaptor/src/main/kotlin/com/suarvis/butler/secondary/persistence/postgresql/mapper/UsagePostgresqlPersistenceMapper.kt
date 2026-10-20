package com.suarvis.butler.secondary.persistence.postgresql.mapper

import com.suarvis.butler.core.domain.budget.Spend
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.budget.UsageTotal
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.persistence.postgresql.entity.RateLimitRow
import com.suarvis.butler.secondary.persistence.postgresql.entity.UsageRow
import com.suarvis.butler.secondary.persistence.postgresql.entity.UsageTotalRow
import java.time.Instant

/** Domain usage and rate limits ↔ rows. */
object UsagePostgresqlPersistenceMapper {
    fun toRow(record: UsageRecord): UsageRow =
        UsageRow(
            recordedAt = record.at,
            provider = record.provider.value,
            model = record.model.value,
            inputTokens = record.usage.inputTokens,
            outputTokens = record.usage.outputTokens,
            cacheCreationInputTokens = record.usage.cacheCreationInputTokens,
            cacheReadInputTokens = record.usage.cacheReadInputTokens,
            costUsd = record.costUsd,
        )

    fun toSummary(rows: List<UsageTotalRow>): UsageSummary =
        UsageSummary(
            rows.map {
                // Postgres sums keep the column's scale (numeric(20,10)); strip it for readable values.
                UsageTotal(ProviderId(it.provider), ModelId(it.model), it.calls, Spend(it.costUsd.stripTrailingZeros(), it.tokens))
            },
        )

    fun toCooldowns(rows: List<RateLimitRow>): Map<ProviderId, Instant> =
        rows.mapNotNull { row -> runCatching { ProviderId(row.provider) }.getOrNull()?.let { it to row.cooldownUntil } }.toMap()

    fun toRow(provider: ProviderId, until: Instant): RateLimitRow = RateLimitRow(provider.value, until)
}
