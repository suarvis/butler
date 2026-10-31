package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.CliEnvironment
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.api.cli.exception.InvalidWorkingDirectoryException
import com.suarvis.butler.api.security.authorization.rules.BroadWorkingDirectoryRule
import com.suarvis.butler.api.security.authorization.rules.WorkingDirectoryRequest
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.usecase.tool.ListToolsUseCase
import org.springframework.stereotype.Component
import picocli.CommandLine.Model.CommandSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything a command needs to know about how it was invoked.
 *
 * @param permissionMode the mode turns run in: `--permission-mode`, `--dangerously-skip-permissions`, or `prompt`.
 * @param allowedTools the tools `--allowedTools` leaves on; null when the flag is absent (every tool).
 */
data class CliInvocation(
    val format: OutputFormat,
    val workspace: WorkspaceRoot,
    val model: ModelId? = null,
    val allowOverBudget: Boolean = false,
    val permissionMode: PermissionMode = PermissionMode.PROMPT,
    val allowedTools: Set<ToolName>? = null,
)

@Component
class CliInvocationResolver(
    private val environment: CliEnvironment,
    private val broadWorkingDirectoryRule: BroadWorkingDirectoryRule,
    private val listTools: ListToolsUseCase,
) {
    /** For commands that work on a workspace: resolves the folder and applies the broad-cwd rule. */
    fun resolve(spec: CommandSpec): CliInvocation {
        val options = globalOptions(spec)
        val permissionMode = permissionMode(options)
        val directory = resolveDirectory(options.firstNotNullOfOrNull { it.workingDirectory })
        broadWorkingDirectoryRule.authorize(
            WorkingDirectoryRequest(directory, environment.homeDirectory, options.any { it.allowBroadCwd }),
        )
        val model = options.firstNotNullOfOrNull { it.model }?.let(::ModelId)
        return CliInvocation(
            format(options),
            WorkspaceRoot.of(directory),
            model,
            options.any { it.allowOverBudget },
            permissionMode,
            allowedTools(options),
        )
    }

    /** For commands that do not touch a workspace (help, version). */
    fun resolveFormat(spec: CommandSpec): OutputFormat = format(globalOptions(spec))

    private fun format(options: List<GlobalOptions>): OutputFormat =
        options.firstNotNullOfOrNull { it.outputFormat }
            ?: OutputFormat.parseOrNull(environment.variable(OutputFormat.ENV_VARIABLE))
            ?: OutputFormat.TEXT

    /**
     * The innermost `--permission-mode` wins, as for every global flag; each value given is checked.
     * `--dangerously-skip-permissions` means danger-full-access, as in Clawcode. Clawcode lets the
     * later of the two flags win; Butler refuses both together, since one of them would be ignored
     * silently and either may have been the one meant.
     */
    private fun permissionMode(options: List<GlobalOptions>): PermissionMode {
        val requested =
            options.mapNotNull { it.permissionMode }.map { label ->
                PermissionFlags.parseMode(label)
                    ?: throw CliUsageException("invalid_permission_mode", "unsupported permission mode '$label'.", PermissionFlags.MODE_USAGE)
            }
        val skip = options.any { it.skipPermissions }
        if (skip && requested.isNotEmpty()) {
            throw CliUsageException(
                "cli_parse",
                "--dangerously-skip-permissions cannot be combined with ${PermissionFlags.MODE_FLAG}",
                "Pass only one: --dangerously-skip-permissions is the same as ${PermissionFlags.MODE_FLAG} danger-full-access.",
            )
        }
        return if (skip) PermissionMode.DANGER_FULL_ACCESS else requested.firstOrNull() ?: PermissionMode.PROMPT
    }

    /**
     * Every `--allowedTools` at every level adds to the list, as repeating the flag does in Clawcode.
     * The tools are listed only when the flag is given, so other runs never ask MCP servers for theirs.
     */
    private fun allowedTools(options: List<GlobalOptions>): Set<ToolName>? {
        val values = options.asReversed().flatMap { it.allowedTools.orEmpty() }
        if (values.isEmpty()) return null
        val tools = listTools.execute()
        return PermissionFlags.parseAllowedTools(values, tools.builtIn + tools.external)
    }

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
