package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.UsageActions
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Command(name = "usage", mixinStandardHelpOptions = true, description = ["Show this month's estimated spend per provider and model, and your budgets."])
class UsageCommand(
    private val resolver: CliInvocationResolver,
    private val usage: UsageActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        usage.report(resolver.resolveFormat(spec))
        return ExitCodes.OK
    }
}
