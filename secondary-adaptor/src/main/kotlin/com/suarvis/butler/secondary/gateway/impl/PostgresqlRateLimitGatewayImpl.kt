package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.secondary.persistence.postgresql.mapper.UsagePostgresqlPersistenceMapper
import com.suarvis.butler.secondary.persistence.postgresql.repository.UsageJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import com.suarvis.butler.secondary.persistence.postgresql.support.postgresqlCall
import java.time.Instant

/** Rate-limit cooldowns in `butler_rate_limit`, one row per provider. */
class PostgresqlRateLimitGatewayImpl(
    private val repository: UsageJdbcRepository,
    private val schema: PostgresqlSchema,
) : RateLimitGateway {
    override fun activeCooldowns(now: Instant): Map<ProviderId, Instant> =
        postgresqlCall(schema, "read rate limits") { UsagePostgresqlPersistenceMapper.toCooldowns(repository.activeCooldowns(now)) }

    override fun record(provider: ProviderId, until: Instant) {
        postgresqlCall(schema, "record a rate limit") { repository.upsertCooldown(UsagePostgresqlPersistenceMapper.toRow(provider, until)) }
    }
}
