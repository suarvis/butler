package com.suarvis.butler.api.cli.action

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.CliEnvironment
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.dto.PromptResultOutput
import com.suarvis.butler.api.cli.exception.CliInterruptedException
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.mapper.TurnCliMapper
import com.suarvis.butler.api.cli.prompt.CliToolApprovers
import com.suarvis.butler.api.cli.prompt.CliTurnApprovers
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.StreamingTurnObserver
import com.suarvis.butler.api.cli.render.TerminalStyle
import com.suarvis.butler.api.cli.repl.TurnInterrupts
import com.suarvis.butler.api.config.CliProperties
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.ToolApprover
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnCancellation
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
    private val interrupts: TurnInterrupts,
    private val cli: CliProperties,
    private val environment: CliEnvironment,
) {
    /**
     * Runs the turn and presents it, interrupted or not; the REPL calls this inside [TurnInterrupts.during].
     *
     * @param approver shared across a REPL session; a one-shot prompt gets its own.
     * @param toolApprover likewise, so "allow for this session" lasts as long as the REPL session.
     * @param cancellation set by Ctrl-C while the turn runs.
     */
    fun run(
        invocation: CliInvocation,
        prompt: String,
        session: SessionReference?,
        compact: Boolean,
        approver: TurnApprover = approvers.create(invocation),
        toolApprover: ToolApprover = toolApprovers.create(invocation),
        cancellation: TurnCancellation = TurnCancellation.NEVER,
    ): TurnSummary {
        val summary = execute(invocation, prompt, session, compact, TurnCallbacks(approver = approver, toolApprover = toolApprover, cancellation = cancellation))
        present(invocation, summary, compact)
        return summary
    }

    /**
     * A one-shot prompt (`butler prompt`, `butler TEXT`). Ctrl-C stops its turn as in the REPL; a
     * stopped turn then ends in the `interrupted` error with exit code 130, so a script can tell it
     * from an answer, and JSON output gets the error envelope instead of a result.
     */
    fun runOnce(
        invocation: CliInvocation,
        prompt: String,
        session: SessionReference?,
        compact: Boolean,
    ): Int {
        val callbacks = TurnCallbacks(approver = approvers.create(invocation), toolApprover = toolApprovers.create(invocation))
        val summary =
            interrupts.during { cancellation ->
                execute(invocation, prompt, session, compact, callbacks.copy(cancellation = cancellation))
            }
        if (summary.interrupted) throw CliInterruptedException(summary.session.id.value)
        present(invocation, summary, compact)
        return ExitCodes.OK
    }

    /** Runs the turn, streaming it in text mode unless [compact]; [callbacks] brings everything but the observer. */
    private fun execute(
        invocation: CliInvocation,
        prompt: String,
        session: SessionReference?,
        compact: Boolean,
        callbacks: TurnCallbacks,
    ): TurnSummary {
        val observer =
            if (streams(invocation, compact)) {
                StreamingTurnObserver(console.out, TerminalStyle.detect(console.interactive, environment.variable(NO_COLOR), cli.color))
            } else {
                TurnObserver.NONE
            }
        return runTurn.execute(
            RunTurnUseCase.Command(
                invocation.workspace,
                prompt,
                session,
                invocation.model,
                permissionMode = invocation.permissionMode,
                allowedTools = invocation.allowedTools,
            ),
            callbacks.copy(observer = observer),
        )
    }

    /** Prints what streaming did not: the final text with --compact, or the JSON result. */
    private fun present(invocation: CliInvocation, summary: TurnSummary, compact: Boolean) {
        when {
            streams(invocation, compact) -> Unit
            invocation.format == OutputFormat.TEXT -> {
                console.out.println(summary.finalText)
                console.out.flush()
            }
            else -> output.success(invocation.format, PromptResultOutput.serializer(), TurnCliMapper.toPromptResult(summary)) { summary.finalText }
        }
    }

    private fun streams(invocation: CliInvocation, compact: Boolean): Boolean = invocation.format == OutputFormat.TEXT && !compact

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

    private companion object {
        const val NO_COLOR = "NO_COLOR"
    }
}
