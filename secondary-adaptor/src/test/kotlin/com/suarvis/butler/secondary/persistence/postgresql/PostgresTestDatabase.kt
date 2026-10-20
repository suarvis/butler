package com.suarvis.butler.secondary.persistence.postgresql

import com.suarvis.butler.secondary.persistence.postgresql.support.PostgresqlSchema
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * One PostgreSQL (with pgvector, as in compose.yaml) shared by every PostgreSQL test in the run,
 * migrated once. Tests are skipped, not failed, when Docker is unavailable; Testcontainers removes
 * the container when the JVM exits.
 */
object PostgresTestDatabase {
    class PostgresContainer(image: DockerImageName) : GenericContainer<PostgresContainer>(image)

    private const val PASSWORD = "contract-test"

    private val dataSource: HikariDataSource? by lazy { if (DockerClientFactory.instance().isDockerAvailable) start() else null }

    /** A clean, migrated database, or a skipped test when Docker is not running. */
    fun cleanDataSource(): HikariDataSource {
        val available = dataSource
        assumeTrue(available != null, "Docker is not available")
        JdbcClient.create(available!!).sql("truncate table butler_session, butler_usage, butler_rate_limit cascade").update()
        return available
    }

    private fun start(): HikariDataSource {
        val container =
            PostgresContainer(DockerImageName.parse("pgvector/pgvector:pg17"))
                .withEnv("POSTGRES_DB", "butler")
                .withEnv("POSTGRES_USER", "butler")
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        container.start()
        return HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/butler"
            username = "butler"
            password = PASSWORD
            maximumPoolSize = 2
        }.also { PostgresqlSchema(it).migrate() }
    }
}
