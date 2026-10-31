package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.dto.ToolListOutput
import com.suarvis.butler.api.cli.mapper.ToolCliMapper
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.ToolTextRenderer
import com.suarvis.butler.core.usecase.tool.ListToolsUseCase
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** `butler tools`, Clawcode's `claw tools`: every tool the model can be offered, with the permission tier it needs. */
@Command(
    name = "tools",
    mixinStandardHelpOptions = true,
    description = ["List available tools and their descriptions, with the permission tier each needs."],
)
class ToolsCommand(
    private val resolver: CliInvocationResolver,
    private val listTools: ListToolsUseCase,
    private val output: CliOutputWriter,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    override fun call(): Int {
        val payload = ToolCliMapper.toOutput(listTools.execute())
        output.success(resolver.resolveFormat(spec), ToolListOutput.serializer(), payload) { ToolTextRenderer.list(payload) }
        return ExitCodes.OK
    }
}
