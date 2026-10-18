package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.dto.HelpOutput
import com.suarvis.butler.api.cli.dto.VersionOutput
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.SessionTextRenderer
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import org.springframework.stereotype.Component

/**
 * `butler --resume SESSION [/cmd ...]`: opens a saved session and runs slash commands against it
 * without starting the REPL. Commands run in order and can change the current session
 * (`/session fork`, `/session switch`), as in Clawcode.
 */
@Component
class ResumeRunner(
    private val sessions: SessionActions,
    private val output: CliOutputWriter,
) {
    fun run(invocation: CliInvocation, rawReference: String, arguments: List<String>): Int {
        // `--resume /status` means "latest, then /status": the option value was really a command.
        val (referenceText, tokens) =
            if (rawReference.startsWith("/")) "latest" to listOf(rawReference) + arguments else rawReference to arguments
        val commands = SlashCommandParser.parse(tokens)
        var current = sessions.load(invocation, SessionReference.parse(referenceText))
        if (commands.isEmpty()) {
            sessions.restored(invocation, current)
            return ExitCodes.OK
        }
        commands.forEach { command -> current = execute(invocation, current, command) }
        return ExitCodes.OK
    }

    private fun execute(invocation: CliInvocation, current: Session, command: SlashCommand): Session =
        when (command) {
            SlashCommand.Status -> current.also { sessions.status(invocation, it) }
            is SlashCommand.Export -> current.also { sessions.export(invocation, it, command.path) }
            SlashCommand.SessionList -> current.also { sessions.list(invocation, PageRequest.firstPage()) }
            is SlashCommand.SessionExists -> current.also { sessions.exists(invocation, command.target) }
            is SlashCommand.SessionSwitch -> sessions.switchTo(invocation, SessionReference.parse(command.target))
            is SlashCommand.SessionFork ->
                sessions.fork(invocation, SessionReference.ById(current.id), command.branchName?.let(::BranchName))
            is SlashCommand.SessionDelete -> current.also { sessions.delete(invocation, SessionReference.parse(command.target)) }
            SlashCommand.Version -> current.also { printVersion(invocation) }
            SlashCommand.Help -> current.also { printHelp(invocation) }
        }

    private fun printVersion(invocation: CliInvocation) {
        val version = ButlerVersion.current
        val payload = VersionOutput(version = version, javaVersion = System.getProperty("java.version"), message = "butler $version")
        output.success(invocation.format, VersionOutput.serializer(), payload) { SessionTextRenderer.version(payload) }
    }

    private fun printHelp(invocation: CliInvocation) {
        val message = "Resume-safe slash commands: ${SlashCommandParser.SUPPORTED}"
        output.success(invocation.format, HelpOutput.serializer(), HelpOutput(message = message)) { message }
    }
}
