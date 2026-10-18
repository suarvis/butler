package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.CliEnvironment
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.exception.InvalidWorkingDirectoryException
import com.suarvis.butler.api.security.authorization.rules.BroadWorkingDirectoryRule
import com.suarvis.butler.api.security.authorization.rules.WorkingDirectoryRequest
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import org.springframework.stereotype.Component
import picocli.CommandLine.Model.CommandSpec
import java.nio.file.Files
import java.nio.file.Path

/** Everything a command needs to know about how it was invoked. */
data class CliInvocation(val format: OutputFormat, val workspace: WorkspaceRoot)

@Component
class CliInvocationResolver(
    private val environment: CliEnvironment,
    private val broadWorkingDirectoryRule: BroadWorkingDirectoryRule,
) {
    /** For commands that work on a workspace: resolves the folder and applies the broad-cwd rule. */
    fun resolve(spec: CommandSpec): CliInvocation {
        val options = globalOptions(spec)
        val directory = resolveDirectory(options.firstNotNullOfOrNull { it.workingDirectory })
        broadWorkingDirectoryRule.authorize(
            WorkingDirectoryRequest(directory, environment.homeDirectory, options.any { it.allowBroadCwd }),
        )
        return CliInvocation(format(options), WorkspaceRoot.of(directory))
    }

    /** For commands that do not touch a workspace (help, version). */
    fun resolveFormat(spec: CommandSpec): OutputFormat = format(globalOptions(spec))

    private fun format(options: List<GlobalOptions>): OutputFormat =
        options.firstNotNullOfOrNull { it.outputFormat }
            ?: OutputFormat.parseOrNull(environment.variable(OutputFormat.ENV_VARIABLE))
            ?: OutputFormat.TEXT

    /** Innermost command first, so a flag after the subcommand overrides one before it. */
    private fun globalOptions(spec: CommandSpec): List<GlobalOptions> =
        generateSequence(spec) { it.parent() }
            .mapNotNull { it.mixins()[GlobalOptions.MIXIN_NAME]?.userObject() as? GlobalOptions }
            .toList()

    private fun resolveDirectory(raw: String?): Path {
        val base = environment.currentDirectory
        val candidate = (if (raw == null) base else base.resolve(raw)).toAbsolutePath().normalize()
        if (!Files.isDirectory(candidate)) throw InvalidWorkingDirectoryException(candidate)
        // Real path so that /tmp and /private/tmp (macOS) name the same workspace.
        return candidate.toRealPath()
    }
}
