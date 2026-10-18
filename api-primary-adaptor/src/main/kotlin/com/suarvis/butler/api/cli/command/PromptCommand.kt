package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.action.PromptActions
import com.suarvis.butler.core.domain.session.SessionReference
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/** `butler prompt [--stdin] [TEXT]`: Clawcode's one-shot prompt. */
@Command(
    name = "prompt",
    mixinStandardHelpOptions = true,
    description = ["Send one prompt and exit. Reads stdin when TEXT is omitted and input is piped."],
)
class PromptCommand(
    private val resolver: CliInvocationResolver,
    private val prompts: PromptActions,
) : Callable<Int> {
    @Mixin(name = GlobalOptions.MIXIN_NAME)
    lateinit var globalOptions: GlobalOptions

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--stdin"], description = ["Also read the prompt from stdin."])
    var readStdin: Boolean = false

    @Option(names = ["--session"], paramLabel = "SESSION", description = ["Continue a saved session (id or latest) instead of starting a new one."])
    var session: String? = null

    @Option(names = ["--compact"], description = ["Print only the final answer, without streaming (text mode)."])
    var compact: Boolean = false

    @Parameters(paramLabel = "TEXT", arity = "0..*", description = ["The prompt."])
    var text: List<String>? = null

    override fun call(): Int {
        val invocation = resolver.resolve(spec)
        prompts.run(invocation, prompts.promptText(text.orEmpty(), readStdin), session?.let(SessionReference::parse), compact)
        return ExitCodes.OK
    }
}
