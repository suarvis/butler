package com.suarvis.butler.secondary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.persistence.*`. Every value can be overridden by environment variables, e.g.
 * `BUTLER_PERSISTENCE_STORE=postgresql`, `BUTLER_PERSISTENCE_POSTGRESQL_PASSWORD=...`.
 * No credentials have defaults.
 */
@ConfigurationProperties(prefix = "butler.persistence")
data class PersistenceProperties(
    val store: Store = Store.FILE,
    val file: File = File(),
    val postgresql: Postgresql = Postgresql(),
) {
    enum class Store { FILE, POSTGRESQL }

    /** Sessions live under `<workspace>/<directory>`; Clawcode used `.claw/sessions`. */
    data class File(val directory: String = ".butler/sessions")

    data class Postgresql(
        val url: String = "jdbc:postgresql://localhost:5432/butler",
        val username: String = "butler",
        val password: String = "",
        val maximumPoolSize: Int = 4,
    )
}
