package com.suarvis.butler.api.cli.action

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.dto.PromptResultOutput
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.mapper.TurnCliMapper
import com.suarvis.butler.api.cli.prompt.CliToolApprovers
import com.suarvis.butler.api.cli.prompt.CliTurnApprovers
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.StreamingTurnObserver
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.ToolApprover
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.domain.turn.TurnSummary
import com.suarvis.butler.core.usecase.turn.RunTurnUseCase
import org.springframework.stereotype.Component

/**
 * Runs one prompt and presents the result: streamed text, final text only (--compact), or JSON. The
 * turn runs in the invocation's permission mode, with only the tools `--allowedTools` leaves on.
 */
@Component
class PromptActions(
    private val runTurn: RunTurnUseCase,
    private val output: CliOutputWriter,
    private val console: CliConsole,
    private val approvers: CliTurnApprovers,
    private val toolApprovers: CliToolApprovers,
) {
    /**
     * @param approver shared across a REPL session; a one-shot prompt gets its own.
     * @param toolApprover likewise, so "allow for this session" lasts as long as the REPL session.
     */
    fun run(
        invocation: CliInvocation,
        prompt: String,
        session: SessionReference?,
        compact: Boolean,
        approver: TurnApprover = approvers.create(invocation),
        toolApprover: ToolApprover = toolApprovers.create(invocation),
    ): TurnSummary {
        val streaming = invocation.format == OutputFormat.TEXT && !compact
        val observer = if (streaming) StreamingTurnObserver(console.out) else TurnObserver.NONE
        val summary =
            runTurn.execute(
                RunTurnUseCase.Command(
                    invocation.workspace,
                    prompt,
                    session,
                    invocation.model,
                    permissionMode = invocation.permissionMode,
                    allowedTools = invocation.allowedTools,
                ),
                TurnCallbacks(observer = observer, approver = approver, toolApprover = toolApprover),
            )
        when {
            streaming -> Unit
            invocation.format == OutputFormat.TEXT -> {
                console.out.println(summary.finalText)
                console.out.flush()
            }
            else -> output.success(invocation.format, PromptResultOutput.serializer(), TurnCliMapper.toPromptResult(summary)) { summary.finalText }
        }
        return summary
    }

    /**
     * Prompt text from arguments and/or stdin, as in Clawcode: stdin is read when no text is
     * given and input is piped, or always with --stdin.
     */
    fun promptText(arguments: List<String>, readStdin: Boolean): String {
        val fromArguments = arguments.joinToString(" ").trim()
        val useStdin = readStdin || (fromArguments.isEmpty() && !console.interactive)
        val fromStdin = if (useStdin) console.input.bufferedReader().readText().trim() else ""
        val prompt = listOf(fromArguments, fromStdin).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (prompt.isEmpty()) {
            throw CliUsageException("missing_argument", "No prompt given", "Pass the prompt as text, e.g. butler prompt \"explain this repo\", or pipe it on stdin.")
        }
        return prompt
    }
}
