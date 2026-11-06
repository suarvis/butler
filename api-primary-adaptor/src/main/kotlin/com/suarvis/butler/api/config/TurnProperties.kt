package com.suarvis.butler.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.tools.*`: whether a turn offers the model any tools. The shipped application.yml turns
 * them on (since gate M1) and `BUTLER_TOOLS_ENABLED=false` turns them off; the Kotlin default stays
 * off, so properties built by hand opt in.
 */
@ConfigurationProperties(prefix = "butler.tools")
data class TurnProperties(
    val enabled: Boolean = false,
)
