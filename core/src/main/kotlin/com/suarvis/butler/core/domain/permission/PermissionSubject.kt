package com.suarvis.butler.core.domain.permission

import com.suarvis.butler.core.domain.tool.ToolInvocation

/**
 * The one string of a call that a rule's argument is matched against, as Clawcode's
 * `extract_permission_subject` (`runtime/src/permissions.rs`) picks it from the call's input: the
 * first string among the keys `command`, `path`, `file_path`, `filePath`, `notebook_path`,
 * `notebookPath`, `url`, `pattern`, `code` and `message`, otherwise the whole input.
 */
object PermissionSubject {
    /**
     * Built-in invocations are already decoded, so their subject is the field that lookup would
     * find: the command for bash, the path for file tools, and for searches the path when the model
     * gave one, otherwise the pattern, because Clawcode tries `path` before `pattern`. Paths are
     * workspace-relative, so `write_file(src/:*)` also covers a model that sent an absolute path
     * inside the workspace.
     *
     * For MCP calls the decoding adaptor has already run that lookup over the JSON input, since core
     * does not parse JSON; with no subject the input is matched whole, Clawcode's fallback.
     */
    fun of(invocation: ToolInvocation): String =
        when (invocation) {
            is ToolInvocation.Bash -> invocation.command
            is ToolInvocation.ReadFile -> invocation.path.value
            is ToolInvocation.WriteFile -> invocation.path.value
            is ToolInvocation.EditFile -> invocation.path.value
            is ToolInvocation.GlobSearch -> invocation.under?.value ?: invocation.pattern
            is ToolInvocation.GrepSearch -> invocation.under?.value ?: invocation.pattern
            is ToolInvocation.External -> invocation.subject ?: invocation.input.value
        }
}
