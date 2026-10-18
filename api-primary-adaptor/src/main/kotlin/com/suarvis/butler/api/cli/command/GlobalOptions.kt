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

    @Option(names = ["--model"], paramLabel = "MODEL", description = ["Model or alias for this run, e.g. sonnet, opus, haiku or a full model id."])
    var model: String? = null

    @Option(names = ["--cwd", "-C", "--directory"], paramLabel = "PATH", description = ["Run as if launched from PATH."])
    var workingDirectory: String? = null

    @Option(names = ["--allow-broad-cwd"], description = ["Allow your home folder or the filesystem root as the workspace."])
    var allowBroadCwd: Boolean = false

    companion object {
        const val MIXIN_NAME = "global"
    }
}
