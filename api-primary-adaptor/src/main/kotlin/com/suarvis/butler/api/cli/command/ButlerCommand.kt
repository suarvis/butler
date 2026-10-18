package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.repl.ResumeRunner
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/**
 * `butler`: mirrors Clawcode's `claw` entry point. Forms supported in this build:
 *   butler --resume [SESSION|latest] [/status] [/export FILE] [/session ...]
 *   butler <subcommand>
 * The interactive REPL and prompt forms (`butler`, `butler prompt TEXT`, `butler TEXT`) arrive
 * with the conversation slice.
 */
@Command(
    name = "butler",
    mixinStandardHelpOptions = true,
    versionProvider = ButlerVersionProvider::class,
    description = ["Butler: a terminal coding agent (Kotlin rewrite of Clawcode)."],
    subcommands = [
        StatusCommand::class,
        SessionCommand::class,
        ExportCommand::class,
        VersionCommand::class,
        HelpCommand::class,
    ],
    footer = [
        "",
        "Examples:",
        "  butler session list",
        "  butler --output-format json status",
        "  butler --resume latest /status /export notes.md",
        "  butler export --session latest --output conversation.md",
    ],
)
class ButlerCommand(
    private val resumeRunner: ResumeRunner,
    private val resolver: CliInvocationResolver,
    private val console: CliConsole,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["--resume"],
        arity = "0..1",
        fallbackValue = "latest",
        paramLabel = "SESSION",
        description = ["Open a saved session (id, or latest/last/recent) and run the slash commands that follow."],
    )
    var resume: String? = null

    @Parameters(paramLabel = "ARGS", description = ["Slash commands for --resume, e.g. /status /export notes.md"])
    var arguments: List<String>? = null

    override fun call(): Int {
        val reference = resume
        val rest = arguments.orEmpty()
        return when {
            reference != null -> resumeRunner.run(resolver.resolve(spec), reference, rest)
            rest.isNotEmpty() ->
                throw CliUsageException(
                    kind = "unsupported_command",
                    message = "Prompting is not available in this build yet: '${rest.joinToString(" ")}'",
                    hint = "Available now: butler status, butler session list, butler export, butler --resume latest /status.",
                )
            else -> {
                spec.commandLine().usage(console.out)
                ExitCodes.OK
            }
        }
    }
}

class ButlerVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("butler ${ButlerVersion.current}")
}
