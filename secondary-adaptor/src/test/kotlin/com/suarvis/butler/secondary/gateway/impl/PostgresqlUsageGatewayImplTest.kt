package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.persistence.postgresql.PostgresTestDatabase
import com.suarvis.butler.secondary.persistence.postgresql.repository.UsageJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import org.junit.jupiter.api.BeforeEach
import org.springframework.jdbc.core.simple.JdbcClient

/** The usage contract against a real PostgreSQL. Skipped without Docker. */
class PostgresqlUsageGatewayImplTest : UsageGatewayContract() {
    private lateinit var gateway: UsageGateway

    @BeforeEach
    fun cleanDatabase() {
        val dataSource = PostgresTestDatabase.cleanDataSource()
        gateway = PostgresqlUsageGatewayImpl(UsageJdbcRepository(JdbcClient.create(dataSource)), PostgresqlSchema(dataSource))
    }

    override fun gateway(): UsageGateway = gateway
}

/** The rate-limit contract against a real PostgreSQL. Skipped without Docker. */
class PostgresqlRateLimitGatewayImplTest : RateLimitGatewayContract() {
    private lateinit var gateway: RateLimitGateway

    @BeforeEach
    fun cleanDatabase() {
        val dataSource = PostgresTestDatabase.cleanDataSource()
        gateway = PostgresqlRateLimitGatewayImpl(UsageJdbcRepository(JdbcClient.create(dataSource)), PostgresqlSchema(dataSource))
    }

    override fun gateway(): RateLimitGateway = gateway
}
