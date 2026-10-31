package com.suarvis.butler.secondary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.permissions.*`: the user's allow, deny and ask rules for tool calls, in Clawcode's rule
 * syntax (`read_file`, `bash(git status)`, `write_file(src/:*)`). Clawcode's separate `deniedTools`
 * list is a deny rule without an argument here.
 *
 * Lists come from YAML or from indexed environment variables such as `BUTLER_PERMISSIONS_DENY_0`;
 * the indexed form matters because Spring splits a single value on commas and a rule may contain one.
 */
@ConfigurationProperties(prefix = "butler.permissions")
data class PermissionProperties(
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val ask: List<String> = emptyList(),
)
