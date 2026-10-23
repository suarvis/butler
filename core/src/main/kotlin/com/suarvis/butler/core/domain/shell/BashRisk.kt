package com.suarvis.butler.core.domain.shell

import com.suarvis.butler.core.domain.tool.PermissionTier

/** What a shell command sets out to do, as lane F's classifier (ported from Clawcode's bash_validation.rs) will judge it. */
enum class CommandIntent {
    READ_ONLY,
    WRITE,
    DESTRUCTIVE,
    NETWORK,
    PROCESS_MANAGEMENT,
    PACKAGE_MANAGEMENT,
    SYSTEM_ADMIN,
    UNKNOWN,
}

/** A command's risk: what it does, the permission it needs, and a warning for the approval prompt. */
data class BashRisk(
    val intent: CommandIntent,
    val tier: PermissionTier,
    val warning: String? = null,
)
