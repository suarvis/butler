package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.mapper.SessionCliMapper
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.session.SessionReference
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** `butler session ...`: Clawcode's `/session [list|exists|switch|fork|delete]` as subcommands. */
@Command(
    name = "session",
    mixinStandardHelpOptions = true,
    description = ["List, check, fork or delete saved sessions in this workspace (default: list)."],
    subcommands = [
        SessionListCommand::class,
        SessionExistsCommand::class,
        SessionForkCommand::class,
        SessionDeleteCommand::class,
    ],
)
class SessionCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        sessions.list(resolver.resolve(spec), PageRequest.firstPage())
        return ExitCodes.OK
    }
}

@Command(name = "list", mixinStandardHelpOptions = true, description = ["List saved sessions, newest first."])
class SessionListCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--page"], paramLabel = "N", description = ["Page number, starting at 1 (default: 1)."])
    var page: Int = 1

    @Option(names = ["--size"], paramLabel = "N", description = ["Sessions per page (default: 50)."])
    var size: Int = PageRequest.DEFAULT_LIMIT

    override fun call(): Int {
        if (page < 1) throw CliUsageException("invalid_input", "--page must be 1 or greater, got $page")
        sessions.list(resolver.resolve(spec), SessionCliMapper.toPageRequest(page, size))
        return ExitCodes.OK
    }
}

@Command(name = "exists", mixinStandardHelpOptions = true, description = ["Check whether a session exists."])
class SessionExistsCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "SESSION", description = ["Session id, or latest."])
    lateinit var target: String

    override fun call(): Int {
        sessions.exists(resolver.resolve(spec), target)
        return ExitCodes.OK
    }
}

@Command(name = "fork", mixinStandardHelpOptions = true, description = ["Copy a session into a new one that remembers its parent."])
class SessionForkCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", arity = "0..1", paramLabel = "BRANCH", description = ["Optional branch name for the fork."])
    var branch: String? = null

    @Option(names = ["--session"], paramLabel = "SESSION", description = ["Session to fork (default: latest)."])
    var source: String = "latest"

    override fun call(): Int {
        sessions.fork(resolver.resolve(spec), SessionReference.parse(source), branch?.let(::BranchName))
        return ExitCodes.OK
    }
}

@Command(name = "delete", mixinStandardHelpOptions = true, description = ["Delete a saved session (requires --force)."])
class SessionDeleteCommand(
    private val resolver: CliInvocationResolver,
    private val sessions: SessionActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "SESSION", description = ["Session id, or latest."])
    lateinit var target: String

    @Option(names = ["--force"], description = ["Confirm the deletion; there is no undo."])
    var force: Boolean = false

    override fun call(): Int {
        if (!force) {
            throw CliUsageException(
                kind = "confirmation_required",
                message = "Deleting session '$target' cannot be undone",
                hint = "Re-run with --force to delete it.",
            )
        }
        sessions.delete(resolver.resolve(spec), SessionReference.parse(target))
        return ExitCodes.OK
    }
}
