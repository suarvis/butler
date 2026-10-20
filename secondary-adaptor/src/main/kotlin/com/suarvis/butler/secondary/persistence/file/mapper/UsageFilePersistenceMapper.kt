package com.suarvis.butler.secondary.persistence.file.mapper

import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.secondary.persistence.file.entity.RateLimitFileRecord
import com.suarvis.butler.secondary.persistence.file.entity.UsageLineRecord
import java.math.BigDecimal
import java.time.Instant

/** Domain usage and rate limits ↔ the per-user JSON files. */
object UsageFilePersistenceMapper {
    fun toLine(record: UsageRecord): UsageLineRecord =
        UsageLineRecord(
            atMs = record.at.toEpochMilli(),
            provider = record.provider.value,
            model = record.model.value,
            inputTokens = record.usage.inputTokens,
            outputTokens = record.usage.outputTokens,
            cacheCreationInputTokens = record.usage.cacheCreationInputTokens,
            cacheReadInputTokens = record.usage.cacheReadInputTokens,
            costUsd = record.costUsd?.toPlainString(),
        )

    fun toDomain(line: UsageLineRecord): UsageRecord =
        UsageRecord(
            at = Instant.ofEpochMilli(line.atMs),
            provider = ProviderId(line.provider),
            model = ModelId(line.model),
            usage = TokenUsage(line.inputTokens, line.outputTokens, line.cacheCreationInputTokens, line.cacheReadInputTokens),
            costUsd = line.costUsd?.let(::BigDecimal),
        )

    /** Entries for provider ids this version cannot read are dropped: a cooldown is only a hint. */
    fun toCooldowns(record: RateLimitFileRecord): Map<ProviderId, Instant> =
        record.cooldownUntilMs.mapNotNull { (provider, untilMs) ->
            runCatching { ProviderId(provider) }.getOrNull()?.let { it to Instant.ofEpochMilli(untilMs) }
        }.toMap()

    fun toRecord(cooldowns: Map<ProviderId, Instant>): RateLimitFileRecord =
        RateLimitFileRecord(cooldowns.entries.associate { (provider, until) -> provider.value to until.toEpochMilli() })
}
