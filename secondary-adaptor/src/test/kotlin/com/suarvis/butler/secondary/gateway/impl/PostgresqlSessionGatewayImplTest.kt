package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.persistence.postgresql.PostgresTestDatabase
import com.suarvis.butler.secondary.persistence.postgresql.repository.SessionJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import org.junit.jupiter.api.BeforeEach
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

/** Runs the shared gateway contract against a real PostgreSQL. Skipped, not failed, when Docker is unavailable. */
class PostgresqlSessionGatewayImplTest : SessionGatewayContract() {
    override val workspace: WorkspaceRoot = WorkspaceRoot.of(Path.of("/work/postgres-contract"))

    private lateinit var gateway: SessionGateway

    @BeforeEach
    fun cleanDatabase() {
        val dataSource = PostgresTestDatabase.cleanDataSource()
        gateway =
            PostgresqlSessionGatewayImpl(
                SessionJdbcRepository(JdbcClient.create(dataSource)),
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                PostgresqlSchema(dataSource),
            )
    }

    override fun gateway(): SessionGateway = gateway
}
