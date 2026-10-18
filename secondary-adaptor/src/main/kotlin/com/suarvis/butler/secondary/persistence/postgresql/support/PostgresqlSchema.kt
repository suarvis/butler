package com.suarvis.butler.secondary.persistence.postgresql.support

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Applies the versioned SQL migrations under `db/migration/postgresql`, once, on first use.
 * Deferred so that CLI commands which never touch sessions never open a connection.
 */
class PostgresqlSchema(private val dataSource: DataSource) {
    private val migration = lazy { migrate() }

    fun ensureMigrated() {
        migration.value
    }

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATIONS)
            .load()
            .migrate()
    }

    private companion object {
        const val MIGRATIONS = "classpath:db/migration/postgresql"
    }
}
