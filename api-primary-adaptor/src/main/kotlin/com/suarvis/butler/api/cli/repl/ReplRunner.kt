package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.action.PromptActions
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.action.UsageActions
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.exception.CliExceptionHandler
import com.suarvis.butler.api.cli.prompt.CliTurnApprovers
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.usecase.model.ResolveModelUseCase
import org.springframework.stereotype.Component

/**
 * Interactive mode (`butler`, `butler --resume SESSION`): each line is a prompt, or a slash command
 * when it starts with `/`. Errors are reported and the loop continues. Reads plain lines from stdin,
 * so it also works with piped input; line editing, history and Ctrl-C interruption come later.
 */
@Component
class ReplRunner(
    private val prompts: PromptActions,
    private val sessions: SessionActions,
    private val usage: UsageActions,
    private val resolveModel: ResolveModelUseCase,
    private val approvers: CliTurnApprovers,
    private val exceptions: CliExceptionHandler,
    private val console: CliConsole,
) {
    private class State(var session: SessionReference.ById?, var invocation: CliInvocation, val approver: TurnApprover)

    fun run(invocation: CliInvocation, resume: SessionReference? = null): Int {
        val interactive = console.interactive
        // One approver for the whole session: approving "over budget" once covers the following prompts.
        val state = State(null, invocation, approvers.create(invocation))
        if (resume != null) {
            val session = sessions.load(invocation, resume)
            state.session = SessionReference.ById(session.id)
            sessions.restored(invocation, session)
        } else if (interactive) {
            say(state, "Butler ${ButlerVersion.current} · ${invocation.workspace} · /help for commands, /exit to leave")
        }
        while (true) {
            if (interactive) {
                console.out.print("> ")
                console.out.flush()
            }
            val line = console.readLine()?.trim() ?: break
            if (line.isEmpty()) continue
            val keepGoing =
                try {
                    if (line.startsWith("/")) slash(state, line) else prompt(state, line)
                } catch (error: Exception) {
                    exceptions.report(error, state.invocation.format)
                    true
                }
            if (!keepGoing) break
        }
        return ExitCodes.OK
    }

    private fun prompt(state: State, text: String): Boolean {
        val summary = prompts.run(state.invocation, text, state.session, compact = false, approver = state.approver)
        state.session = SessionReference.ById(summary.session.id)
        return true
    }

    private fun slash(state: State, line: String): Boolean {
        val invocation = state.invocation
        for (command in SlashCommandParser.parse(line.split(Regex("\\s+")))) {
            when (command) {
                SlashCommand.Exit -> return false
                SlashCommand.Clear -> {
                    state.session = null
                    say(state, "Started a fresh session; the next prompt opens it.")
                }
                is SlashCommand.Model -> model(state, command.name)
                SlashCommand.Status -> sessions.status(invocation, state.session?.let { sessions.load(invocation, it) })
                SlashCommand.Usage -> usage.report(invocation.format)
                is SlashCommand.Export -> sessions.export(invocation, sessions.load(invocation, current(state)), command.path)
                SlashCommand.SessionList -> sessions.list(invocation, PageRequest.firstPage())
                is SlashCommand.SessionExists -> sessions.exists(invocation, command.target)
                is SlashCommand.SessionSwitch ->
                    state.session = SessionReference.ById(sessions.switchTo(invocation, SessionReference.parse(command.target)).id)
                is SlashCommand.SessionFork ->
                    state.session =
                        SessionReference.ById(sessions.fork(invocation, current(state), command.branchName?.let(::BranchName)).id)
                is SlashCommand.SessionDelete -> sessions.delete(invocation, SessionReference.parse(command.target))
                SlashCommand.Version -> say(state, "butler ${ButlerVersion.current}")
                SlashCommand.Help ->
                    say(state, "Type a prompt, or a slash command: ${SlashCommandParser.SUPPORTED}, ${SlashCommandParser.INTERACTIVE_ONLY}")
            }
        }
        return true
    }

    /**
     * `/model` shows the model the next prompt will use; `/model NAME` checks NAME and switches to
     * it, even to another provider: the next turn sends the whole conversation to the new model.
     */
    private fun model(state: State, name: String?) {
        if (name == null) {
            val current = currentModel(state)
            say(state, "Model: ${current.id} (${current.provider.id})")
            return
        }
        val target = resolveModel.execute(ModelId(name))
        // A session may name a model that is no longer configured; that must not block switching away from it.
        val previous = runCatching { currentModel(state) }.getOrNull()
        state.invocation = state.invocation.copy(model = target.id)
        val providerChange =
            if (previous != null && previous.provider.id != target.provider.id) {
                " Switching provider ${previous.provider.id} → ${target.provider.id}; the conversation so far goes along."
            } else {
                ""
            }
        say(state, "Model set to ${target.id} (${target.provider.id}) for the next prompts.$providerChange")
    }

    private fun currentModel(state: State): ModelProfile =
        resolveModel.execute(state.invocation.model ?: state.session?.let { sessions.load(state.invocation, it).model })

    /** The open session, or the latest one when no prompt has been sent yet. */
    private fun current(state: State): SessionReference = state.session ?: SessionReference.Latest

    /** Conversational messages are text only; in JSON mode stdout carries only JSON objects. */
    private fun say(state: State, message: String) {
        if (state.invocation.format == OutputFormat.TEXT) {
            console.out.println(message)
            console.out.flush()
        }
    }
}
