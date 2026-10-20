package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.persistence.postgresql.mapper.UsagePostgresqlPersistenceMapper
import com.suarvis.butler.secondary.persistence.postgresql.repository.UsageJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import com.suarvis.butler.secondary.persistence.postgresql.support.postgresqlCall
import java.time.Instant

/** Usage ledger in `butler_usage`; the database does the aggregation. */
class PostgresqlUsageGatewayImpl(
    private val repository: UsageJdbcRepository,
    private val schema: PostgresqlSchema,
) : UsageGateway {
    override fun record(record: UsageRecord) {
        postgresqlCall(schema, "record usage") { repository.insert(UsagePostgresqlPersistenceMapper.toRow(record)) }
    }

    override fun summarize(from: Instant, to: Instant): UsageSummary =
        postgresqlCall(schema, "read the usage ledger") { UsagePostgresqlPersistenceMapper.toSummary(repository.totals(from, to)) }
}
