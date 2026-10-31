package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.permission.PermissionDecision
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionPolicy
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.BashRisk
import com.suarvis.butler.core.domain.shell.BashRiskClassifier
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprover
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.gateway.CommandGateway
import com.suarvis.butler.core.gateway.McpGateway
import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway

/**
 * Runs one tool call the model made, from its raw JSON input to the result that goes back to it:
 * decode the input, check the permission, hand it to the port that runs that kind of tool, wrap
 * the outcome.
 *
 * The permission step sits between decoding and running, as in Clawcode's `run_turn`:
 * [PermissionPolicy] decides from the session's mode and the user's rules; a call it asks about
 * goes to the [ToolApprover], and whichever allow answer comes back runs it once. Remembering
 * "allow for this session" is the approver's job, since use cases keep no state between calls.
 *
 * Every failure becomes an error result instead of an exception: a tool the turn did not offer,
 * input that does not decode, a call the policy or the person refuses, a port that throws. The
 * model reads the error and can react, by fixing the field it got wrong or trying another tool,
 * and the transcript stays valid because every call still gets its answer. Clawcode does the same:
 * its tool errors and permission denials become result text with `is_error` set, and the loop
 * carries on.
 */
class RunToolCallUseCase(
    private val inputs: ToolInputGateway,
    private val files: WorkspaceGateway,
    private val commands: CommandGateway,
    private val mcp: McpGateway,
) {
    /**
     * @param tool what the turn offered under [call]'s name; null when the model called a tool
     *   this turn did not offer.
     * @param mode the session's permission mode, for [PermissionPolicy].
     * @param rules the user's permission rules, read once per turn by the caller.
     * @param cancellation polled after an approval prompt and by a running shell command, so
     *   cancelling the turn stops the call.
     */
    data class Command(
        val workspace: WorkspaceRoot,
        val call: ContentBlock.ToolUse,
        val tool: ToolDefinition?,
        val mode: PermissionMode,
        val rules: PermissionRules,
        val cancellation: TurnCancellation = TurnCancellation.NEVER,
    )

    /** @param approver asked when the policy wants a person to decide. */
    fun execute(
        command: Command,
        approver: ToolApprover,
    ): ContentBlock.ToolResult {
        val call = command.call
        val outcome =
            if (command.tool == null) {
                // Clawcode's wording (tools/src/lib.rs), so a model used to it reads the same answer.
                ToolOutcome("unsupported tool: ${call.name}", isError = true)
            } else {
                run(command, command.tool, approver)
            }
        return ContentBlock.ToolResult(call.id, call.name, outcome.output, outcome.isError)
    }

    private fun run(
        command: Command,
        tool: ToolDefinition,
        approver: ToolApprover,
    ): ToolOutcome {
        val call = command.call
        return try {
            val invocation = inputs.decode(command.workspace, call.name, call.input)
            // No sandbox enforces anything yet (lane F), so a command is judged by what it would do unconfined.
            val bashRisk = (invocation as? ToolInvocation.Bash)?.let { BashRiskClassifier.classify(it.command, command.workspace, sandboxActive = false) }
            when (val decision = PermissionPolicy.decide(invocation, tool, command.mode, command.rules, bashRisk)) {
                PermissionDecision.Allow -> route(command, invocation)
                is PermissionDecision.Deny -> ToolOutcome(decision.reason, isError = true)
                is PermissionDecision.Ask -> askThenRoute(command, tool, invocation, decision, bashRisk, approver)
            }
        } catch (invalid: InvalidToolInputException) {
            // Already names the field that is wrong, which is exactly what the model needs to retry.
            ToolOutcome(invalid.message ?: "Invalid input for ${call.name}", isError = true)
        } catch (failure: RuntimeException) {
            ToolOutcome("Tool ${call.name} failed: ${failure.message ?: failure.javaClass.simpleName}", isError = true)
        }
    }

    private fun askThenRoute(
        command: Command,
        tool: ToolDefinition,
        invocation: ToolInvocation,
        decision: PermissionDecision.Ask,
        bashRisk: BashRisk?,
        approver: ToolApprover,
    ): ToolOutcome {
        // The tier the policy compared the mode with: a command's own risk for bash, the tool's otherwise.
        val tier = bashRisk?.tier ?: tool.tier
        val request = ToolApprovalRequest(command.call, invocation, tier, command.mode, decision.reason, preview(command.workspace, invocation), bashRisk)
        val answer = approver.decide(request)
        // The turn may have been cancelled while the prompt waited, often by answering it with Ctrl-C;
        // Clawcode checks at the same point, whatever the answer was.
        if (command.cancellation.isCancelled()) return ToolOutcome(INTERRUPTED_RESULT, isError = true)
        return when (answer) {
            ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_FOR_SESSION -> route(command, invocation)
            // Clawcode's wording (rusty-claude-cli/src/main.rs, CliPermissionPrompter).
            ToolApprovalDecision.DENY -> ToolOutcome("tool '${command.call.name}' denied by user approval prompt", isError = true)
        }
    }

    /** What the person asked sees: the command for bash, the raw input for MCP, whatever the file tools can show (an edit's diff). */
    private fun preview(
        workspace: WorkspaceRoot,
        invocation: ToolInvocation,
    ): String? =
        when (invocation) {
            is ToolInvocation.Bash -> invocation.command
            is ToolInvocation.External -> invocation.input.value
            is ToolInvocation.ReadFile,
            is ToolInvocation.GlobSearch,
            is ToolInvocation.GrepSearch,
            is ToolInvocation.WriteFile,
            is ToolInvocation.EditFile,
            -> files.preview(workspace, invocation)
        }

    /** Exhaustive on purpose: a new kind of invocation does not compile until it is given a port. */
    private fun route(command: Command, invocation: ToolInvocation): ToolOutcome =
        when (invocation) {
            is ToolInvocation.ReadFile,
            is ToolInvocation.GlobSearch,
            is ToolInvocation.GrepSearch,
            is ToolInvocation.WriteFile,
            is ToolInvocation.EditFile,
            -> files.run(command.workspace, invocation)
            // Workspace-only until lane F decides the sandbox per turn.
            is ToolInvocation.Bash ->
                commands.run(command.workspace, invocation, SandboxPolicy.forWorkspace(command.workspace), command.cancellation)
            is ToolInvocation.External -> mcp.call(invocation.tool, invocation.input)
        }

    internal companion object {
        /** Clawcode's wording (`conversation.rs`) for a call the turn was cancelled before it could run. */
        const val INTERRUPTED_RESULT = "Interrupted by user before this tool could run."
    }
}
