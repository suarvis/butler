package com.suarvis.butler.api.cli.command

import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.exception.CliUsageException
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.tool.ToolDefinition
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

    /** Kept as text and checked by [CliInvocationResolver], so a bad value gets Clawcode's message and error kind. */
    @Option(
        names = [PermissionFlags.MODE_FLAG],
        paramLabel = "MODE",
        description = ["Set read-only, workspace-write, danger-full-access, prompt, or allow (default: prompt)"],
    )
    var permissionMode: String? = null

    @Option(names = ["--dangerously-skip-permissions", "--skip-permissions"], description = ["Skip all permission checks"])
    var skipPermissions: Boolean = false

    /** Each occurrence holds one value, itself a comma- or space-separated list, as Clawcode reads it. */
    @Option(
        names = [PermissionFlags.ALLOWED_TOOLS_FLAG, "--allowed-tools"],
        paramLabel = "TOOLS",
        description = [
            "Restrict enabled tools by canonical snake_case name or alias",
            "Examples: read, glob, write_file; may be repeated",
        ],
    )
    var allowedTools: List<String>? = null

    companion object {
        const val MIXIN_NAME = "global"
    }
}

/**
 * The values of the permission flags and of `/permissions`, read as Clawcode's
 * `normalize_permission_mode` and `GlobalToolRegistry::normalize_allowed_tools`
 * (`rusty-claude-cli/src/main.rs`, `tools/src/lib.rs`) read them.
 */
object PermissionFlags {
    const val MODE_FLAG = "--permission-mode"
    const val ALLOWED_TOOLS_FLAG = "--allowedTools"

    /** Clawcode's usage line, with the two modes Butler's flag also accepts. */
    const val MODE_USAGE = "Usage: $MODE_FLAG read-only|workspace-write|danger-full-access|prompt|allow"

    /**
     * Clawcode's aliases. `default` is Clawcode's name for read-only, not Butler's default mode,
     * which is `prompt`; it is kept so that scripts written for Clawcode mean the same here.
     */
    private val MODE_ALIASES =
        mapOf(
            "default" to PermissionMode.READ_ONLY,
            "plan" to PermissionMode.READ_ONLY,
            "acceptEdits" to PermissionMode.WORKSPACE_WRITE,
            "auto" to PermissionMode.WORKSPACE_WRITE,
            "dontAsk" to PermissionMode.DANGER_FULL_ACCESS,
            "bypassPermissions" to PermissionMode.DANGER_FULL_ACCESS,
            "dangerFullAccess" to PermissionMode.DANGER_FULL_ACCESS,
        )

    /** Clawcode's `allowed_tool_aliases`, kept in its sorted order for the error hint. */
    private val TOOL_ALIASES =
        sortedMapOf(
            "read" to "read_file",
            "Read" to "read_file",
            "write" to "write_file",
            "Write" to "write_file",
            "edit" to "edit_file",
            "Edit" to "edit_file",
            "glob" to "glob_search",
            "Glob" to "glob_search",
            "grep" to "grep_search",
            "Grep" to "grep_search",
        )

    private val LIST_SEPARATORS = Regex("[,\\s]+")

    /**
     * A mode label (`read-only`, `workspace-write`, `danger-full-access`, `prompt`, `allow`) or one of
     * Clawcode's aliases; null for anything else. Case matters, as in Clawcode (`acceptEdits`).
     * Clawcode's flag rejects `prompt` and `allow`; Butler accepts them because `prompt` is its default.
     */
    fun parseMode(label: String): PermissionMode? {
        val trimmed = label.trim()
        return PermissionMode.entries.firstOrNull { it.cliName == trimmed } ?: MODE_ALIASES[trimmed]
    }

    /**
     * The tools `--allowedTools` leaves on, among [tools] (the built-in and MCP tools a turn can
     * offer). Names compare as Clawcode compares them: `ReadFile`, `read-file` and `read_file` are the
     * same tool, and `read`, `write`, `edit`, `glob` and `grep` are aliases.
     *
     * Clawcode stops at the first unknown name; Butler names every unknown one in the same message,
     * so a script can fix them all at once.
     */
    fun parseAllowedTools(values: List<String>, tools: List<ToolDefinition>): Set<ToolName> {
        val names = tools.map { it.name }.distinct()
        val canonicalNames = names.map { canonical(it.value) }.toSortedSet()
        val canonicalByKey = mutableMapOf<String, String>()
        for (name in names) canonicalByKey[lookupKey(name.value)] = canonical(name.value)
        for ((alias, target) in TOOL_ALIASES) {
            if (target in canonicalNames) canonicalByKey[lookupKey(alias)] = target
        }

        val tokens = values.flatMap { it.split(LIST_SEPARATORS) }.filter { it.isNotEmpty() }
        val unknown = tokens.filter { lookupKey(it) !in canonicalByKey }
        if (unknown.isNotEmpty()) {
            throw CliUsageException(
                "invalid_tool_name",
                "unsupported tool in $ALLOWED_TOOLS_FLAG: ${unknown.distinct().joinToString(", ")}",
                "Available: ${canonicalNames.joinToString(", ")}\n" +
                    "Aliases: ${aliases(names).entries.joinToString(", ") { (alias, target) -> "$alias=$target" }}\n" +
                    "Hint: Use canonical snake_case tool names from Available or aliases from Aliases.",
            )
        }
        val allowed = tokens.mapNotNull { canonicalByKey[lookupKey(it)] }.toSet()
        if (allowed.isEmpty()) {
            throw CliUsageException(
                "missing_argument",
                "$ALLOWED_TOOLS_FLAG was provided with no usable tool names (got `${values.joinToString(" ")}`). Omit the flag to allow all tools.",
                "Usage: $ALLOWED_TOOLS_FLAG <tool-name>[,<tool-name>...]  e.g. $ALLOWED_TOOLS_FLAG read,glob",
            )
        }
        // Core compares exact names, so hand it the tools' own names rather than Clawcode's canonical forms.
        return names.filter { canonical(it.value) in allowed }.toSet()
    }

    /** The fixed aliases plus every tool whose own name differs from its canonical form, as Clawcode lists them. */
    private fun aliases(names: List<ToolName>): Map<String, String> {
        val aliases = TOOL_ALIASES.toSortedMap()
        for (name in names) {
            val form = canonical(name.value)
            if (form != name.value) aliases[name.value] = form
        }
        return aliases
    }

    /** Clawcode's `canonical_allowed_tool_name`: snake_case, `-` read as `_`, camelCase split at its humps. */
    private fun canonical(value: String): String {
        val chars = value.trim().replace('-', '_').toCharArray()
        val output = StringBuilder()
        for ((index, ch) in chars.withIndex()) {
            if (ch == '_' || ch.isWhitespace()) {
                output.append('_')
                continue
            }
            val previous = chars.getOrNull(index - 1)
            val next = chars.getOrNull(index + 1)
            val startsWord = previous != null && (previous in 'a'..'z' || previous in '0'..'9') || next != null && next in 'a'..'z'
            if (ch in 'A'..'Z' && index > 0 && !output.endsWith('_') && startsWord) output.append('_')
            output.append(if (ch in 'A'..'Z') ch + ('a' - 'A') else ch)
        }
        return output.toString().trim('_')
    }

    /** Clawcode's `allowed_tool_lookup_key`: the canonical form without underscores. */
    private fun lookupKey(value: String): String = canonical(value).replace("_", "")
}
