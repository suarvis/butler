package com.suarvis.butler.secondary.persistence.postgresql.support

import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.GatewayException
import org.flywaydb.core.api.FlywayException
import org.springframework.dao.DataAccessException

/** Runs a storage call after the schema is migrated and turns storage failures into the core's gateway error. */
inline fun <T> postgresqlCall(schema: PostgresqlSchema, action: String, block: () -> T): T =
    try {
        schema.ensureMigrated()
        block()
    } catch (error: FlywayException) {
        throw GatewayException("Could not $action: database schema migration failed (${error.message})", error)
    } catch (error: DataAccessException) {
        throw GatewayException("Could not $action: ${error.mostSpecificCause.message}", error)
    } catch (error: GatewayException) {
        throw error
    } catch (error: ButlerException) {
        throw GatewayException("Could not $action: stored data is invalid (${error.message})", error)
    }
