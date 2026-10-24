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
 * Decides whether a tool call may run, as Clawcode's `PermissionPolicy::authorize_with_context`
 * (`runtime/src/permissions.rs`) does, without its hooks, which Butler adds after v1. Plain core
 * code over typed invocations, so every adaptor gets the same answer for the same call and the
 * rules are testable without a terminal.
 */
object PermissionPolicy {
    /**
     * The first question answered yes decides, in Clawcode's order:
     *
     * 1. A deny rule matches: Deny. Clawcode's separate `deniedTools` list is a deny rule without
     *    an argument here.
     * 2. An ask rule matches: Ask, even in `allow` mode.
     * 3. An allow rule matches, or the mode [covers] the required tier: Allow. An allow rule is an
     *    explicit grant for the call, so it outranks the mode.
     * 4. The mode is `prompt`, or `workspace-write` meets a call that needs danger-full-access: Ask.
     * 5. Otherwise: Deny.
     *
     * Rules match the tool's name and the call's [PermissionSubject]. The required tier is the
     * tool's, except for a bash call given a [bashRisk]: then it is the command's tier, higher or
     * lower, as Clawcode's `PermissionEnforcer::check_with_required_mode` takes a tier worked out
     * from the command. A [bashRisk] passed with any other call is ignored, since it describes a
     * command and that call has none. Reasons are Clawcode's, word for word: an Ask reason is shown
     * to the person asked, a Deny reason reaches the model as the tool's error.
     */
    fun decide(
        invocation: ToolInvocation,
        tool: ToolDefinition,
        mode: PermissionMode,
        rules: PermissionRules,
        bashRisk: BashRisk? = null,
    ): PermissionDecision {
        val subject = PermissionSubject.of(invocation)
        rules.deny.firstOrNull { it.matches(tool.name, subject) }?.let { pattern ->
            return PermissionDecision.Deny("Permission to use ${tool.name} has been denied by rule '${pattern.rule}'")
        }
        rules.ask.firstOrNull { it.matches(tool.name, subject) }?.let { pattern ->
            return PermissionDecision.Ask("tool '${tool.name}' requires approval due to ask rule '${pattern.rule}'")
        }
        val required = if (invocation is ToolInvocation.Bash && bashRisk != null) bashRisk.tier else tool.tier
        if (rules.allow.any { it.matches(tool.name, subject) } || mode.covers(required)) {
            return PermissionDecision.Allow
        }
        if (mode == PermissionMode.PROMPT || (mode == PermissionMode.WORKSPACE_WRITE && required == PermissionTier.DANGER_FULL_ACCESS)) {
            return PermissionDecision.Ask("tool '${tool.name}' requires approval to escalate from ${mode.cliName} to ${required.cliName}")
        }
        return PermissionDecision.Deny("tool '${tool.name}' requires ${required.cliName} permission; current mode is ${mode.cliName}")
    }
}
