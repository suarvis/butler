package com.suarvis.butler.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.tools.*`: whether a turn offers the model any tools. Off until gate M1, so tools that
 * are still being built never reach users. `BUTLER_TOOLS_ENABLED=true` switches them on.
 */
@ConfigurationProperties(prefix = "butler.tools")
data class TurnProperties(
    val enabled: Boolean = false,
)
