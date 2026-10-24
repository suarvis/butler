package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.gateway.CommandGateway
import com.suarvis.butler.core.gateway.McpGateway
import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway

/**
 * Runs one tool call the model made, from its raw JSON input to the result that goes back to it:
 * decode the input, hand it to the port that runs that kind of tool, wrap the outcome.
 *
 * Every failure becomes an error result instead of an exception: a tool the turn did not offer,
 * input that does not decode, a port that throws. The model reads the error and can react, by
 * fixing the field it got wrong or trying another tool, and the transcript stays valid because
 * every call still gets its answer. Clawcode does the same: its tool errors become result text
 * with `is_error` set, and the loop carries on.
 *
 * The permission check comes between decoding and running (A5). [Command.mode] and
 * [Command.rules] are carried for it already, so callers do not change when it lands.
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
     * @param cancellation polled by a running shell command, so cancelling the turn stops it.
     */
    data class Command(
        val workspace: WorkspaceRoot,
        val call: ContentBlock.ToolUse,
        val tool: ToolDefinition?,
        val mode: PermissionMode,
        val rules: PermissionRules,
        val cancellation: TurnCancellation = TurnCancellation.NEVER,
    )

    fun execute(command: Command): ContentBlock.ToolResult {
        val call = command.call
        val outcome =
            if (command.tool == null) {
                // Clawcode's wording (tools/src/lib.rs), so a model used to it reads the same answer.
                ToolOutcome("unsupported tool: ${call.name}", isError = true)
            } else {
                run(command)
            }
        return ContentBlock.ToolResult(call.id, call.name, outcome.output, outcome.isError)
    }

    private fun run(command: Command): ToolOutcome {
        val call = command.call
        return try {
            route(command, inputs.decode(command.workspace, call.name, call.input))
        } catch (invalid: InvalidToolInputException) {
            // Already names the field that is wrong, which is exactly what the model needs to retry.
            ToolOutcome(invalid.message ?: "Invalid input for ${call.name}", isError = true)
        } catch (failure: RuntimeException) {
            ToolOutcome("Tool ${call.name} failed: ${failure.message ?: failure.javaClass.simpleName}", isError = true)
        }
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
}
