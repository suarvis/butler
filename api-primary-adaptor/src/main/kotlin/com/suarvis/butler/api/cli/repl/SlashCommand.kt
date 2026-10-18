package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.exception.CliUsageException

/**
 * Slash commands usable with `--resume` today and inside the REPL later. Names and arguments
 * follow Clawcode's resume-safe commands.
 */
sealed interface SlashCommand {
    data object Status : SlashCommand

    data class Export(val path: String?) : SlashCommand

    data object SessionList : SlashCommand

    data class SessionExists(val target: String) : SlashCommand

    data class SessionSwitch(val target: String) : SlashCommand

    data class SessionFork(val branchName: String?) : SlashCommand

    data class SessionDelete(val target: String) : SlashCommand

    data object Version : SlashCommand

    data object Help : SlashCommand
}

/** Splits `/status /export notes.md /session fork try-b` into commands with their arguments. */
object SlashCommandParser {
    const val SUPPORTED = "/status, /export [file], /session [list|exists ID|switch ID|fork [branch]|delete ID], /version, /help"

    fun parse(tokens: List<String>): List<SlashCommand> {
        if (tokens.isEmpty()) return emptyList()
        if (!tokens.first().startsWith("/")) {
            throw CliUsageException("unsupported_resumed_command", "Expected a slash command but got '${tokens.first()}'", "Supported: $SUPPORTED")
        }
        val groups = mutableListOf<MutableList<String>>()
        for (token in tokens) {
            if (token.startsWith("/")) groups.add(mutableListOf(token)) else groups.last().add(token)
        }
        return groups.map { toCommand(it.first().removePrefix("/").lowercase(), it.drop(1)) }
    }

    private fun toCommand(name: String, args: List<String>): SlashCommand =
        when (name) {
            "status" -> noArguments(name, args, SlashCommand.Status)
            "export" -> SlashCommand.Export(atMostOne(name, args))
            "version" -> noArguments(name, args, SlashCommand.Version)
            "help" -> noArguments(name, args, SlashCommand.Help)
            "session" -> session(args)
            else -> throw CliUsageException("unsupported_resumed_command", "Unknown or unsupported slash command '/$name'", "Supported: $SUPPORTED")
        }

    private fun session(args: List<String>): SlashCommand {
        val action = args.firstOrNull()?.lowercase() ?: return SlashCommand.SessionList
        val rest = args.drop(1)
        return when (action) {
            "list" -> noArguments("session list", rest, SlashCommand.SessionList)
            "exists" -> SlashCommand.SessionExists(exactlyOne("session exists", rest))
            "switch" -> SlashCommand.SessionSwitch(exactlyOne("session switch", rest))
            "fork" -> SlashCommand.SessionFork(atMostOne("session fork", rest))
            "delete" -> SlashCommand.SessionDelete(exactlyOne("session delete", rest.filterNot { it == "--force" }))
            else -> throw CliUsageException("unsupported_resumed_command", "Unknown /session action '$action'", "Supported: $SUPPORTED")
        }
    }

    private fun noArguments(name: String, args: List<String>, command: SlashCommand): SlashCommand {
        if (args.isNotEmpty()) throw CliUsageException("cli_parse", "/$name takes no arguments, got: ${args.joinToString(" ")}")
        return command
    }

    private fun atMostOne(name: String, args: List<String>): String? {
        if (args.size > 1) throw CliUsageException("cli_parse", "/$name takes at most one argument, got: ${args.joinToString(" ")}")
        return args.firstOrNull()
    }

    private fun exactlyOne(name: String, args: List<String>): String {
        if (args.size != 1) throw CliUsageException("missing_argument", "/$name needs exactly one session id")
        return args.single()
    }
}
