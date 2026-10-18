package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.core.domain.session.SessionReference
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Command(name = "status", mixinStandardHelpOptions = true, description = ["Show the workspace and a session's status (default: latest)."])
class StatusCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--session"], paramLabel = "SESSION", description = ["Session to show (default: latest)."])
    var session: String? = null

    override fun call(): Int {
        val invocation = resolver.resolve(spec)
        sessions.status(invocation, session?.let { sessions.load(invocation, SessionReference.parse(it)) })
        return ExitCodes.OK
    }
}

@Command(name = "export", mixinStandardHelpOptions = true, description = ["Export a session as Markdown to a file or stdout."])
class ExportCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", arity = "0..1", paramLabel = "PATH", description = ["File to write (same as --output)."])
    var path: String? = null

    @Option(names = ["--output"], paramLabel = "PATH", description = ["File to write; stdout when omitted."])
    var output: String? = null

    @Option(names = ["--session"], paramLabel = "SESSION", description = ["Session to export (default: latest)."])
    var session: String = "latest"

    override fun call(): Int {
        val positional = path
        val flag = output
        if (positional != null && flag != null && positional != flag) {
            throw CliUsageException("cli_parse", "Give the output path once: either PATH or --output")
        }
        val invocation = resolver.resolve(spec)
        sessions.export(invocation, sessions.load(invocation, SessionReference.parse(session)), positional ?: flag)
        return ExitCodes.OK
    }
}
