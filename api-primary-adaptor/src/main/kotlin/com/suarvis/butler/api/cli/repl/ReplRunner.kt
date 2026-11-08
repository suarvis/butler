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
import com.suarvis.butler.api.cli.prompt.CliToolApprovers
import com.suarvis.butler.api.cli.prompt.CliTurnApprovers
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.ToolApprover
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.usecase.model.ResolveModelUseCase
import org.springframework.stereotype.Component

/**
 * Interactive mode (`butler`, `butler --resume SESSION`): each line is a prompt, or a slash command
 * when it starts with `/`. Errors are reported and the loop continues. At a terminal the console is
 * a line editor with history; piped input is read line by line. Ctrl-C clears a typed line, or
 * leaves on an empty one; during a turn it stops the turn ([TurnInterrupts]) and the REPL goes on.
 */
@Component
class ReplRunner(
    private val prompts: PromptActions,
    private val sessions: SessionActions,
    private val usage: UsageActions,
    private val resolveModel: ResolveModelUseCase,
    private val approvers: CliTurnApprovers,
    private val toolApprovers: CliToolApprovers,
    private val exceptions: CliExceptionHandler,
    private val console: CliConsole,
    private val interrupts: TurnInterrupts,
) {
    private class State(
        var session: SessionReference.ById?,
        var invocation: CliInvocation,
        val approver: TurnApprover,
        val toolApprover: ToolApprover,
    )

    fun run(invocation: CliInvocation, resume: SessionReference? = null): Int {
        val interactive = console.interactive
        // One approver of each kind for the whole session: approving "over budget" or "allow for this
        // session" once covers the following prompts.
        val state = State(null, invocation, approvers.create(invocation), toolApprovers.create(invocation))
        if (resume != null) {
            val session = sessions.load(invocation, resume)
            state.session = SessionReference.ById(session.id)
            sessions.restored(invocation, session)
        } else if (interactive) {
            say(state, "Butler ${ButlerVersion.current} · ${invocation.workspace} · /help for commands, /exit to leave")
        }
        while (true) {
            // "" when Ctrl-C cleared a typed line; null at the end of input or on Ctrl-C at an empty line.
            val line = console.readLine(if (interactive) PROMPT else "")?.trim() ?: break
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
        val summary =
            interrupts.during { cancellation ->
                prompts.run(
                    state.invocation,
                    text,
                    state.session,
                    compact = false,
                    approver = state.approver,
                    toolApprover = state.toolApprover,
                    cancellation = cancellation,
                )
            }
        state.session = SessionReference.ById(summary.session.id)
        // Clawcode's spinner ends such a turn with "⏹ Interrupted"; Butler prints the word without the symbol.
        if (summary.interrupted) say(state, "Interrupted")
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
                is SlashCommand.Permissions -> permissions(state, command.mode)
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
                    say(state, "Type a prompt, or a slash command: ${SlashCommandParser.SUPPORTED}, ${SlashCommandParser.INTERACTIVE_ONLY}\n\n$KEYS_HELP")
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

    /**
     * `/permissions` shows the mode the next tool calls run in; `/permissions MODE` switches to it for
     * the rest of the session, as Clawcode's `set_permissions` does. Asking for the current mode
     * shows the report again instead of a switch.
     */
    private fun permissions(state: State, mode: PermissionMode?) {
        val current = state.invocation.permissionMode
        if (mode == null || mode == current) {
            say(state, permissionsReport(current))
            return
        }
        state.invocation = state.invocation.copy(permissionMode = mode)
        say(state, permissionsSwitchReport(current, mode))
    }

    private fun currentModel(state: State): ModelProfile =
        resolveModel.execute(state.invocation.model ?: state.session?.let { sessions.load(state.invocation, it).model })

    /** The open session, or the latest one when no prompt has been sent yet. */
    private fun current(state: State): SessionReference = state.session ?: SessionReference.Latest

    /**
     * Clawcode's `format_permissions_report`, with Butler's two extra modes listed after its three.
     * Their descriptions are Butler's: Clawcode's report does not list them.
     */
    private fun permissionsReport(mode: PermissionMode): String {
        val modes =
            PERMISSION_MODE_DESCRIPTIONS.map { (each, description) ->
                val marker = if (each == mode) "● current" else "○ available"
                "  ${each.cliName.padEnd(18)} ${marker.padEnd(11)} $description"
            }
        val header = listOf("Permissions", "  Active mode      ${mode.cliName}", "  Mode status      live session default", "", "Modes")
        val usage = listOf("", "Usage", "  Inspect current mode with /permissions", "  Switch modes with /permissions <mode>")
        return (header + modes + usage).joinToString("\n")
    }

    /** Clawcode's `format_permissions_switch_report`. */
    private fun permissionsSwitchReport(previous: PermissionMode, next: PermissionMode): String =
        """
            |Permissions updated
            |  Result           mode switched
            |  Previous mode    ${previous.cliName}
            |  Active mode      ${next.cliName}
            |  Applies to       subsequent tool calls
            |  Usage            /permissions to inspect current mode
        """.trimMargin()

    /** Conversational messages are text only; in JSON mode stdout carries only JSON objects. */
    private fun say(state: State, message: String) {
        if (state.invocation.format == OutputFormat.TEXT) {
            console.out.println(message)
            console.out.flush()
        }
    }

    private companion object {
        const val PROMPT = "> "

        /**
         * The keys, in Clawcode's `render_repl_help` layout and words where Butler has the key. Tab
         * completion, Esc and multi-line input are not there yet, so they are left out, and Ctrl-C
         * takes Esc's place for stopping a turn; Ctrl-D is Butler's addition to the list.
         */
        val KEYS_HELP =
            listOf(
                "Keys",
                "  Up/Down              Navigate prompt history",
                "  Ctrl-R               Reverse-search prompt history",
                "  Ctrl-C               Clear input (or exit on empty prompt)",
                "  Ctrl-C during a turn Interrupt the running turn (twice to quit)",
                "  Ctrl-D               Exit on empty prompt",
            ).joinToString("\n")

        val PERMISSION_MODE_DESCRIPTIONS =
            linkedMapOf(
                PermissionMode.READ_ONLY to "Read/search tools only",
                PermissionMode.WORKSPACE_WRITE to "Edit files inside the workspace",
                PermissionMode.DANGER_FULL_ACCESS to "Unrestricted tool access",
                PermissionMode.PROMPT to "Read freely; ask before anything else",
                PermissionMode.ALLOW to "Every tool call runs without asking",
            )
    }
}
