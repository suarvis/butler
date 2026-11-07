package com.suarvis.butler.api.config

import com.suarvis.butler.core.domain.turn.TurnSettings
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.tools.*`: whether a turn offers the model any tools. The shipped application.yml turns
 * them on (since gate M1) and `BUTLER_TOOLS_ENABLED=false` turns them off; the Kotlin default stays
 * off, so properties built by hand opt in.
 *
 * @param maxIterations the most model calls one turn makes before it stops with `iteration_limit`
 *   (`BUTLER_TOOLS_MAXITERATIONS`); core refuses values below 1.
 */
@ConfigurationProperties(prefix = "butler.tools")
data class TurnProperties(
    val enabled: Boolean = false,
    val maxIterations: Int = TurnSettings.DEFAULT_MAX_ITERATIONS,
)
