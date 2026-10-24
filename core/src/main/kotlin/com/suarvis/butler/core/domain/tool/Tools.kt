package com.suarvis.butler.core.domain.tool

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.ensure
import java.time.Duration

/**
 * The permission a tool needs before it may run: Clawcode's per-tool `required_permission`. The
 * session's permission mode is compared against it.
 */
enum class PermissionTier {
    READ_ONLY,
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS,
}

/**
 * A tool as the model sees it, mirroring Clawcode's `ToolSpec`. The schema stays JSON text because
 * core has no JSON library; provider adaptors send it unchanged.
 */
data class ToolDefinition(
    val name: ToolName,
    val description: String,
    val inputSchema: JsonText,
    val tier: PermissionTier,
) {
    init {
        ensure(description.isNotBlank()) { "Tool $name needs a description" }
    }
}

/**
 * A tool call's input after decoding. Typed, so permission rules, path checks and bash risk stay
 * plain core code that reads fields instead of parsing JSON. Null fields are ones the model left
 * out; the adaptor that runs the tool owns their defaults.
 */
sealed interface ToolInvocation {
    data class ReadFile(val path: WorkspacePath, val offset: Int? = null, val limit: Int? = null) : ToolInvocation

    data class GlobSearch(val pattern: String, val under: WorkspacePath? = null) : ToolInvocation

    /** Every field of Clawcode's `grep_search`, so models used to it behave the same. */
    data class GrepSearch(
        val pattern: String,
        val under: WorkspacePath? = null,
        val glob: String? = null,
        val outputMode: String? = null,
        val before: Int? = null,
        val after: Int? = null,
        val context: Int? = null,
        val lineNumbers: Boolean? = null,
        val ignoreCase: Boolean? = null,
        val fileType: String? = null,
        val headLimit: Int? = null,
        val offset: Int? = null,
        val multiline: Boolean? = null,
    ) : ToolInvocation

    data class WriteFile(val path: WorkspacePath, val content: String) : ToolInvocation

    data class EditFile(
        val path: WorkspacePath,
        val oldText: String,
        val newText: String,
        val replaceAll: Boolean = false,
    ) : ToolInvocation

    /** No sandbox overrides: Butler's policy decides the sandbox, not the model. */
    data class Bash(val command: String, val timeout: Duration? = null, val description: String? = null) : ToolInvocation

    /**
     * MCP and later tools: core passes the input through untouched. [subject] is the string a
     * permission rule's argument is matched against, picked from the input by the adaptor that
     * decoded it, because core does not parse JSON; null means the whole input is matched.
     */
    data class External(val tool: ToolName, val input: JsonText, val subject: String? = null) : ToolInvocation
}

/** What a tool hands back to the model. A failure is an outcome the model can react to, never an exception. */
data class ToolOutcome(
    val output: String,
    val isError: Boolean = false,
    /** For people, not the model: a diff for edits, the command for bash. */
    val preview: String? = null,
)
