package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.gateway.impl.PostgresqlRateLimitGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.PostgresqlSessionGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.PostgresqlUsageGatewayImpl
import com.suarvis.butler.secondary.persistence.postgresql.repository.SessionJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.repository.UsageJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Opt-in store (`butler.persistence.store=postgresql`) for sessions, the usage ledger and rate
 * limits. Creating these beans opens no connection: the pool connects and migrations run on the
 * first query.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "butler.persistence", name = ["store"], havingValue = "postgresql")
class PostgresqlPersistenceConfig {
    @Bean(destroyMethod = "close")
    fun butlerDataSource(properties: PersistenceProperties): HikariDataSource {
        val postgresql = properties.postgresql
        return HikariDataSource().apply {
            jdbcUrl = postgresql.url
            username = postgresql.username
            password = postgresql.password
            maximumPoolSize = postgresql.maximumPoolSize
            poolName = "butler"
        }
    }

    @Bean
    fun postgresqlSchema(dataSource: DataSource): PostgresqlSchema = PostgresqlSchema(dataSource)

    @Bean
    fun butlerJdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)

    @Bean
    fun butlerTransactionTemplate(dataSource: DataSource): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Bean
    fun sessionJdbcRepository(jdbcClient: JdbcClient): SessionJdbcRepository = SessionJdbcRepository(jdbcClient)

    @Bean
    fun sessionGateway(
        repository: SessionJdbcRepository,
        transactions: TransactionTemplate,
        schema: PostgresqlSchema,
    ): SessionGateway = PostgresqlSessionGatewayImpl(repository, transactions, schema)

    @Bean
    fun usageJdbcRepository(jdbcClient: JdbcClient): UsageJdbcRepository = UsageJdbcRepository(jdbcClient)

    @Bean
    fun usageGateway(
        repository: UsageJdbcRepository,
        schema: PostgresqlSchema,
    ): UsageGateway = PostgresqlUsageGatewayImpl(repository, schema)

    @Bean
    fun rateLimitGateway(
        repository: UsageJdbcRepository,
        schema: PostgresqlSchema,
    ): RateLimitGateway = PostgresqlRateLimitGatewayImpl(repository, schema)
}
