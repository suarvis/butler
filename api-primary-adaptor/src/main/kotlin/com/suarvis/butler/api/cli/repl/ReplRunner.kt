package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.action.PromptActions
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.exception.CliExceptionHandler
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.session.SessionReference
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
    private val exceptions: CliExceptionHandler,
    private val console: CliConsole,
) {
    private class State(var session: SessionReference.ById?, var invocation: CliInvocation)

    fun run(invocation: CliInvocation, resume: SessionReference? = null): Int {
        val interactive = console.interactive
        val state = State(null, invocation)
        if (resume != null) {
            val session = sessions.load(invocation, resume)
            state.session = SessionReference.ById(session.id)
            sessions.restored(invocation, session)
        } else if (interactive) {
            say(state, "Butler ${ButlerVersion.current} · ${invocation.workspace} · /help for commands, /exit to leave")
        }
        val reader = console.input.bufferedReader()
        while (true) {
            if (interactive) {
                console.out.print("> ")
                console.out.flush()
            }
            val line = reader.readLine()?.trim() ?: break
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
        val summary = prompts.run(state.invocation, text, state.session, compact = false)
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
                is SlashCommand.Model -> {
                    val name = command.name
                    if (name == null) {
                        say(state, "Model: ${invocation.model ?: "default (or the session's model)"}")
                    } else {
                        state.invocation = invocation.copy(model = ModelId(name))
                        say(state, "Model set to $name for the next prompts.")
                    }
                }
                SlashCommand.Status -> sessions.status(invocation, state.session?.let { sessions.load(invocation, it) })
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
