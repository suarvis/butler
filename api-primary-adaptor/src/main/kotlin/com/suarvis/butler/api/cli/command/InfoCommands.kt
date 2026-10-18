package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ButlerVersion
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.dto.HelpOutput
import com.suarvis.butler.api.cli.dto.VersionOutput
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.SessionTextRenderer
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Command(name = "version", description = ["Show version and runtime information."])
class VersionCommand(
    private val resolver: CliInvocationResolver,
    private val output: CliOutputWriter,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        val version = ButlerVersion.current
        val payload =
            VersionOutput(
                version = version,
                javaVersion = System.getProperty("java.version"),
                message = "butler $version",
            )
        output.success(resolver.resolveFormat(spec), VersionOutput.serializer(), payload) { SessionTextRenderer.version(payload) }
        return ExitCodes.OK
    }
}

@Command(name = "help", description = ["Show usage (also available as --help)."])
class HelpCommand(
    private val resolver: CliInvocationResolver,
    private val output: CliOutputWriter,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        val usage = spec.root().commandLine().usageMessage.trimEnd()
        output.success(resolver.resolveFormat(spec), HelpOutput.serializer(), HelpOutput(message = usage)) { usage }
        return ExitCodes.OK
    }
}
