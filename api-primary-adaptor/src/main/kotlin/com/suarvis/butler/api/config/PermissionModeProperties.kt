package com.suarvis.butler.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.permissions.default-mode`: the permission mode a run starts in when no flag picks one
 * (`BUTLER_PERMISSIONS_DEFAULTMODE`). Unset or `prompt`, Butler asks before every write; set it to
 * `workspace-write` to never be asked about changes inside the project. Any label
 * `--permission-mode` accepts works here, Clawcode's aliases included.
 *
 * The allow, deny and ask rules under the same prefix are bound by the secondary adaptor. This one
 * is the CLI's, because the CLI resolves the mode a run starts in: a flag, then this setting, then
 * `prompt`. Kept as text so that a wrong value is reported by name when a command runs, as a wrong
 * flag is, rather than as a start-up failure.
 */
@ConfigurationProperties(prefix = "butler.permissions")
data class PermissionModeProperties(
    val defaultMode: String? = null,
)
