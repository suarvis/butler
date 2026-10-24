package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.tool.PermissionTier

/**
 * Whether a session in this mode may run a call that needs [tier] without asking, as Clawcode's
 * `PermissionMode::covers` answers it (`runtime/src/permissions.rs`).
 *
 * Written out case by case, never by enum order: Clawcode once ranked modes by declaration order,
 * and `prompt`, declared after danger-full-access, then allowed every tool without asking. Only the
 * three tier modes are ranked (read-only < workspace-write < danger-full-access); a tier added
 * later is covered by none of the lower modes until someone says so here.
 *
 * `allow` and `prompt` are ways of operating, not tiers: `allow` covers everything; `prompt` covers
 * read-only calls and asks for anything more. That is Butler's one departure from Clawcode, whose
 * `prompt` asks even before a read: reads change nothing, and asking for every one would leave a
 * script, which cannot answer, unable to read the project at all.
 */
fun PermissionMode.covers(tier: PermissionTier): Boolean =
    when (this) {
        PermissionMode.ALLOW -> true
        PermissionMode.PROMPT -> tier == PermissionTier.READ_ONLY
        PermissionMode.READ_ONLY -> tier == PermissionTier.READ_ONLY
        PermissionMode.WORKSPACE_WRITE -> tier == PermissionTier.READ_ONLY || tier == PermissionTier.WORKSPACE_WRITE
        PermissionMode.DANGER_FULL_ACCESS -> true
    }

/**
 * The tier under the name Clawcode prints for it, e.g. `danger-full-access`. Clawcode's tiers are
 * modes, so the name is the matching mode's; reasons and prompts then read as Clawcode's do.
 */
val PermissionTier.cliName: String
    get() =
        when (this) {
            PermissionTier.READ_ONLY -> PermissionMode.READ_ONLY.cliName
            PermissionTier.WORKSPACE_WRITE -> PermissionMode.WORKSPACE_WRITE.cliName
            PermissionTier.DANGER_FULL_ACCESS -> PermissionMode.DANGER_FULL_ACCESS.cliName
        }
