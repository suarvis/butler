package com.suarvis.butler.api.cli.prompt

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.cliName
import com.suarvis.butler.core.domain.shell.CommandIntent
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprover
import org.springframework.stereotype.Component

/**
 * Asks whether a tool call may run with Clawcode's approval prompt (`CliPermissionPrompter` in
 * `rusty-claude-cli/src/main.rs`): `y` or `yes` allows it, anything else or the end of input denies
 * it. A script is never asked: without a terminal, or with JSON output, every request is denied.
 *
 * Butler adds three things to Clawcode's yes-or-no prompt: the preview (a diff for an edit, the
 * command for bash) and the bash risk warning under Clawcode's block, and a third answer, `a` or
 * `always`, that allows such calls for the rest of the session. Session grants live in this object
 * and are never stored in the session, so they last exactly as long as it does: one prompt, or a
 * whole REPL session when the REPL keeps one approver.
 */
class CliToolApprover(
    private val console: CliConsole,
    private val format: OutputFormat,
) : ToolApprover {
    /**
     * What one `a` answer allows again without asking. File writes are granted per tool, bash per
     * exact command. The policy's reason is part of the key, so a grant given because the mode asks
     * does not silence an ask rule the person configured, which gives a different reason.
     */
    private data class SessionGrant(val tool: ToolName, val command: String?, val reason: String)

    private val granted = mutableSetOf<SessionGrant>()
    private val denied = mutableListOf<ToolApprovalRequest>()

    /** Every request this approver denied, asked or not, in order, so a script's result can list them. */
    val denials: List<ToolApprovalRequest>
        get() = denied.toList()

    override fun decide(request: ToolApprovalRequest): ToolApprovalDecision {
        val grant = grantFor(request)
        if (grant != null && grant in granted) return ToolApprovalDecision.ALLOW_ONCE
        if (!console.interactive || format != OutputFormat.TEXT) {
            // With JSON output a script reads the denials from the result, so stderr stays quiet.
            if (format == OutputFormat.TEXT) {
                console.err.println("denied ${request.call.name.value}: ${request.reason}")
                console.err.flush()
            }
            return deny(request)
        }
        return ask(request, grant)
    }

    private fun ask(request: ToolApprovalRequest, grant: SessionGrant?): ToolApprovalDecision {
        val out = console.out
        out.println()
        out.println("Permission approval required")
        out.println("  Tool             ${request.call.name.value}")
        out.println("  Current mode     ${request.mode.cliName}")
        out.println("  Required mode    ${request.tier.cliName}")
        if (request.reason.isNotBlank()) out.println("  Reason           ${request.reason}")
        out.println("  Input            ${truncate(request.call.input.value)}")
        request.preview?.trimEnd('\n')?.takeIf { it.isNotBlank() }?.let { out.println(it) }
        request.bashRisk?.warning?.takeIf { request.invocation is ToolInvocation.Bash }?.let { out.println("warning: $it") }
        out.print(if (grant != null) "Approve this tool call? [y/a/N]: " else "Approve this tool call? [y/N]: ")
        out.flush()
        val answer = console.readLine()?.trim()?.lowercase()
        return when {
            answer == "y" || answer == "yes" -> ToolApprovalDecision.ALLOW_ONCE
            grant != null && (answer == "a" || answer == "always") -> {
                granted += grant
                ToolApprovalDecision.ALLOW_FOR_SESSION
            }
            else -> deny(request)
        }
    }

    private fun deny(request: ToolApprovalRequest): ToolApprovalDecision {
        denied += request
        return ToolApprovalDecision.DENY
    }

    /**
     * The grant an `a` answer would give, or null when `a` is not offered. Only file writes and bash
     * qualify. A bash command qualifies only with the classifier's verdict and never when that
     * verdict is destructive, so a remembered approval can never run `rm -rf` unseen.
     */
    private fun grantFor(request: ToolApprovalRequest): SessionGrant? =
        when (val invocation = request.invocation) {
            is ToolInvocation.WriteFile, is ToolInvocation.EditFile -> SessionGrant(request.call.name, null, request.reason)
            is ToolInvocation.Bash ->
                request.bashRisk
                    ?.takeIf { it.intent != CommandIntent.DESTRUCTIVE }
                    ?.let { SessionGrant(request.call.name, invocation.command, request.reason) }
            else -> null
        }

    /**
     * Clawcode prints the whole input, but `write_file` content can run to thousands of lines; the
     * preview shows the change itself. Cut as Clawcode's `truncate_for_summary` cuts, by characters.
     */
    private fun truncate(input: String): String {
        if (input.codePointCount(0, input.length) <= INPUT_LIMIT) return input
        return input.substring(0, input.offsetByCodePoints(0, INPUT_LIMIT)) + "…"
    }

    private companion object {
        const val INPUT_LIMIT = 500
    }
}

/** Builds one tool approver per CLI run (or REPL session), so "allow for this session" lasts exactly that long. */
@Component
class CliToolApprovers(
    private val console: CliConsole,
) {
    fun create(invocation: CliInvocation): CliToolApprover = CliToolApprover(console, invocation.format)
}
