package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.shell.BashRisk
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation

/** Clawcode's five permission modes, with the names its CLI uses. */
enum class PermissionMode(val cliName: String) {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access"),
    PROMPT("prompt"),
    ALLOW("allow"),
}

/** What the policy says about one tool call: run it, ask whoever runs the turn, or refuse. */
sealed interface PermissionDecision {
    data object Allow : PermissionDecision

    /** [reason] is shown to the person asked. */
    data class Ask(val reason: String) : PermissionDecision

    /** [reason] goes back to the model as the tool's error result, so it can try something else. */
    data class Deny(val reason: String) : PermissionDecision
}

/** A tool and an optional pattern its argument must match; lane D defines the pattern syntax. */
data class ToolPattern(
    val tool: ToolName,
    val argument: String? = null,
)

/** The user's allow, deny and ask rules. Configuration supplies them through a gateway, since core cannot read configuration. */
data class PermissionRules(
    val allow: List<ToolPattern> = emptyList(),
    val deny: List<ToolPattern> = emptyList(),
    val ask: List<ToolPattern> = emptyList(),
) {
    companion object {
        val NONE = PermissionRules()
    }
}

/**
 * Decides whether a tool call may run. Plain core code over typed invocations, so every adaptor
 * gets the same answer for the same call and the rules are testable without a terminal.
 */
object PermissionPolicy {
    /** Placeholder until lane D: read-only tools are allowed, anything else asks. Lane D replaces the body, never the signature. */
    @Suppress("UNUSED_PARAMETER")
    fun decide(
        invocation: ToolInvocation,
        tool: ToolDefinition,
        mode: PermissionMode,
        rules: PermissionRules,
        bashRisk: BashRisk? = null,
    ): PermissionDecision =
        when (tool.tier) {
            PermissionTier.READ_ONLY -> PermissionDecision.Allow
            PermissionTier.WORKSPACE_WRITE, PermissionTier.DANGER_FULL_ACCESS ->
                PermissionDecision.Ask("${tool.name} needs ${tool.tier.name.lowercase().replace('_', '-')} permission")
        }
}
