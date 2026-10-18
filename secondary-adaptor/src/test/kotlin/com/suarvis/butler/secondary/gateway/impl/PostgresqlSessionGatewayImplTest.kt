package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.persistence.postgresql.repository.SessionJdbcRepository
import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path

/**
 * Runs the shared gateway contract against a real PostgreSQL (with pgvector, as in compose.yaml).
 * Skipped, not failed, when Docker is unavailable.
 */
class PostgresqlSessionGatewayImplTest : SessionGatewayContract() {
    override val workspace: WorkspaceRoot = WorkspaceRoot.of(Path.of("/work/postgres-contract"))

    private lateinit var gateway: SessionGateway

    @BeforeEach
    fun cleanDatabase() {
        val jdbc = JdbcClient.create(dataSource)
        jdbc.sql("truncate table butler_session cascade").update()
        gateway =
            PostgresqlSessionGatewayImpl(
                SessionJdbcRepository(jdbc),
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                PostgresqlSchema(dataSource),
            )
    }

    override fun gateway(): SessionGateway = gateway

    class PostgresContainer(image: DockerImageName) : GenericContainer<PostgresContainer>(image)

    companion object {
        private const val PASSWORD = "contract-test"
        private lateinit var container: PostgresContainer
        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
            container =
                PostgresContainer(DockerImageName.parse("pgvector/pgvector:pg17"))
                    .withEnv("POSTGRES_DB", "butler")
                    .withEnv("POSTGRES_USER", "butler")
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
            container.start()
            dataSource =
                HikariDataSource().apply {
                    jdbcUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/butler"
                    username = "butler"
                    password = PASSWORD
                    maximumPoolSize = 2
                }
            PostgresqlSchema(dataSource).migrate()
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            if (::dataSource.isInitialized) dataSource.close()
            if (::container.isInitialized) container.stop()
        }
    }
}
