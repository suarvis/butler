package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.PromptActions
import com.suarvis.butler.api.cli.repl.ReplRunner
import com.suarvis.butler.api.cli.repl.ResumeRunner
import com.suarvis.butler.core.domain.session.SessionReference
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/**
 * `butler`: mirrors Clawcode's `claw` entry point.
 *   butler                                   interactive REPL
 *   butler TEXT                              one-shot prompt (shorthand for `butler prompt TEXT`)
 *   butler --resume [SESSION|latest]         REPL continuing a saved session
 *   butler --resume SESSION /status /export  run slash commands against a saved session and exit
 *   butler <subcommand>
 */
@Command(
    name = "butler",
    mixinStandardHelpOptions = true,
    versionProvider = ButlerVersionProvider::class,
    description = ["Butler: a terminal coding agent (Kotlin rewrite of Clawcode)."],
    subcommands = [
        PromptCommand::class,
        StatusCommand::class,
        SessionCommand::class,
        ExportCommand::class,
        VersionCommand::class,
        HelpCommand::class,
    ],
    footer = [
        "",
        "Examples:",
        "  butler \"explain the build setup\"",
        "  butler --model opus prompt \"review this design\"",
        "  git diff | butler prompt --stdin \"write a commit message\"",
        "  butler --resume latest",
        "  butler session list",
        "  butler --output-format json status",
        "  butler --resume latest /status /export notes.md",
        "  butler export --session latest --output conversation.md",
    ],
)
class ButlerCommand(
    private val resumeRunner: ResumeRunner,
    private val replRunner: ReplRunner,
    private val prompts: PromptActions,
    private val resolver: CliInvocationResolver,
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

    @Parameters(paramLabel = "ARGS", description = ["A prompt, or slash commands after --resume (e.g. /status /export notes.md)."])
    var arguments: List<String>? = null

    override fun call(): Int {
        val invocation = resolver.resolve(spec)
        val reference = resume
        val rest = arguments.orEmpty()
        return when {
            reference != null && rest.isEmpty() && !reference.startsWith("/") -> replRunner.run(invocation, SessionReference.parse(reference))
            reference != null -> resumeRunner.run(invocation, reference, rest)
            rest.isNotEmpty() -> {
                prompts.run(invocation, prompts.promptText(rest, readStdin = false), session = null, compact = false)
                ExitCodes.OK
            }
            else -> replRunner.run(invocation)
        }
    }
}

class ButlerVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("butler ${ButlerVersion.current}")
}
