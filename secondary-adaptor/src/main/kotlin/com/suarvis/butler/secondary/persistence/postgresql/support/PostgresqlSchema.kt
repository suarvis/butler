package com.suarvis.butler.secondary.persistence.postgresql.support

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Applies the versioned SQL migrations under `db/migration/postgresql`. */
class PostgresqlSchema(private val dataSource: DataSource) {
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
