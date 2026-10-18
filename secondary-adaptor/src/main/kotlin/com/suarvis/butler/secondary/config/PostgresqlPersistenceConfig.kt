package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.gateway.impl.PostgresqlSessionGatewayImpl
import com.suarvis.butler.secondary.persistence.postgresql.repository.SessionJdbcRepository
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
 * Opt-in store (`butler.persistence.store=postgresql`). Beans are created lazily, so commands
 * that never touch sessions never open a connection.
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
    fun postgresqlSchema(dataSource: DataSource): PostgresqlSchema = PostgresqlSchema(dataSource).also { it.migrate() }

    // Taking the schema as a parameter makes Spring run migrations before the first query.
    @Bean
    @Suppress("UNUSED_PARAMETER")
    fun butlerJdbcClient(dataSource: DataSource, schema: PostgresqlSchema): JdbcClient = JdbcClient.create(dataSource)

    @Bean
    fun butlerTransactionTemplate(dataSource: DataSource): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Bean
    fun sessionJdbcRepository(jdbcClient: JdbcClient): SessionJdbcRepository = SessionJdbcRepository(jdbcClient)

    @Bean
    fun sessionGateway(repository: SessionJdbcRepository, transactions: TransactionTemplate): SessionGateway =
        PostgresqlSessionGatewayImpl(repository, transactions)
}
