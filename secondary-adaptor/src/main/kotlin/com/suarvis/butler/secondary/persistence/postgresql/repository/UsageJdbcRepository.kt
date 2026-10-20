package com.suarvis.butler.secondary.persistence.postgresql.repository

import com.suarvis.butler.secondary.persistence.postgresql.entity.RateLimitRow
import com.suarvis.butler.secondary.persistence.postgresql.entity.UsageRow
import com.suarvis.butler.secondary.persistence.postgresql.entity.UsageTotalRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** SQL for the usage ledger and rate-limit cooldowns. */
class UsageJdbcRepository(private val jdbc: JdbcClient) {
    fun insert(row: UsageRow) {
        jdbc.sql(
            """
            insert into butler_usage (recorded_at, provider, model, input_tokens, output_tokens,
                                      cache_creation_input_tokens, cache_read_input_tokens, cost_usd)
            values (:recordedAt, :provider, :model, :inputTokens, :outputTokens, :cacheCreation, :cacheRead, :costUsd)
            """.trimIndent(),
        )
            .param("recordedAt", row.recordedAt.toOffsetDateTime())
            .param("provider", row.provider)
            .param("model", row.model)
            .param("inputTokens", row.inputTokens)
            .param("outputTokens", row.outputTokens)
            .param("cacheCreation", row.cacheCreationInputTokens)
            .param("cacheRead", row.cacheReadInputTokens)
            .param("costUsd", row.costUsd, java.sql.Types.NUMERIC)
            .update()
    }

    /** Totals for `from <= recorded_at < to`; unpriced rows add tokens but no cost. */
    fun totals(from: Instant, to: Instant): List<UsageTotalRow> =
        jdbc.sql(
            """
            select provider, model, count(*) as calls,
                   sum(input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens) as tokens,
                   coalesce(sum(cost_usd), 0) as cost_usd
            from butler_usage
            where recorded_at >= :from and recorded_at < :to
            group by provider, model
            order by provider, model
            """.trimIndent(),
        )
            .param("from", from.toOffsetDateTime())
            .param("to", to.toOffsetDateTime())
            .query(
                RowMapper { rs, _ ->
                    UsageTotalRow(
                        provider = rs.getString("provider"),
                        model = rs.getString("model"),
                        calls = rs.getLong("calls"),
                        tokens = rs.getLong("tokens"),
                        costUsd = rs.getBigDecimal("cost_usd"),
                    )
                },
            )
            .list()

    fun activeCooldowns(now: Instant): List<RateLimitRow> =
        jdbc.sql("select provider, cooldown_until from butler_rate_limit where cooldown_until > :now")
            .param("now", now.toOffsetDateTime())
            .query(RowMapper { rs, _ -> RateLimitRow(rs.getString("provider"), rs.getObject("cooldown_until", OffsetDateTime::class.java).toInstant()) })
            .list()

    fun upsertCooldown(row: RateLimitRow) {
        jdbc.sql(
            """
            insert into butler_rate_limit (provider, cooldown_until) values (:provider, :until)
            on conflict (provider) do update set cooldown_until = excluded.cooldown_until
            """.trimIndent(),
        )
            .param("provider", row.provider)
            .param("until", row.cooldownUntil.toOffsetDateTime())
            .update()
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
