package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.OutputFormat
import picocli.CommandLine.Option

/**
 * Flags accepted before or after any subcommand, as in Clawcode (`butler --output-format json
 * session list` and `butler session list --output-format json` are equivalent). Every command
 * mixes these in; [CliInvocationResolver] merges them, the innermost value winning.
 */
class GlobalOptions {
    @Option(
        names = [OutputFormat.FLAG],
        paramLabel = "FORMAT",
        description = ["Output format: text or json (case-insensitive). BUTLER_OUTPUT_FORMAT sets the default."],
    )
    var outputFormat: OutputFormat? = null

    @Option(
        names = ["--model"],
        paramLabel = "MODEL",
        description = ["Model for this run: an alias (sonnet, opus, gpt, grok), a full id, or provider/model such as ollama/llama3.2."],
    )
    var model: String? = null

    @Option(names = ["--cwd", "-C", "--directory"], paramLabel = "PATH", description = ["Run as if launched from PATH."])
    var workingDirectory: String? = null

    @Option(names = ["--allow-broad-cwd"], description = ["Allow your home folder or the filesystem root as the workspace."])
    var allowBroadCwd: Boolean = false

    @Option(
        names = ["--allow-over-budget"],
        description = ["Go over butler.budgets limits without asking (for scripts; a warning is printed)."],
    )
    var allowOverBudget: Boolean = false

    companion object {
        const val MIXIN_NAME = "global"
    }
}
