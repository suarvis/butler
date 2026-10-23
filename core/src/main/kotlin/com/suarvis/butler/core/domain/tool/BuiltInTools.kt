package com.suarvis.butler.core.domain.tool

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName

/**
 * The six tools Butler ships, with Clawcode's names, descriptions, schemas and tiers, copied from
 * `mvp_tool_specs()` in `rust/crates/tools/src/lib.rs` at Clawcode commit 22fe360 (v0.1.7). Keeping
 * them identical means a model behaves the same in both. One deliberate cut: `bash` takes only
 * `command`, `timeout` (milliseconds) and `description`, because the sandbox is Butler's policy
 * to decide, not the model's.
 */
object BuiltInTools {
    val READ_FILE =
        ToolDefinition(
            name = ToolName("read_file"),
            description = "Read a text file from the workspace.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string" },
                        "offset": { "type": "integer", "minimum": 0 },
                        "limit": { "type": "integer", "minimum": 1 }
                      },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.READ_ONLY,
        )

    val GLOB_SEARCH =
        ToolDefinition(
            name = ToolName("glob_search"),
            description = "Find files by glob pattern.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "pattern": { "type": "string" },
                        "path": { "type": "string" }
                      },
                      "required": ["pattern"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.READ_ONLY,
        )

    val GREP_SEARCH =
        ToolDefinition(
            name = ToolName("grep_search"),
            description = "Search file contents with a regex pattern.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "pattern": { "type": "string" },
                        "path": { "type": "string" },
                        "glob": { "type": "string" },
                        "output_mode": { "type": "string" },
                        "-B": { "type": "integer", "minimum": 0 },
                        "-A": { "type": "integer", "minimum": 0 },
                        "-C": { "type": "integer", "minimum": 0 },
                        "context": { "type": "integer", "minimum": 0 },
                        "-n": { "type": "boolean" },
                        "-i": { "type": "boolean" },
                        "type": { "type": "string" },
                        "head_limit": { "type": "integer", "minimum": 1 },
                        "offset": { "type": "integer", "minimum": 0 },
                        "multiline": { "type": "boolean" }
                      },
                      "required": ["pattern"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.READ_ONLY,
        )

    val WRITE_FILE =
        ToolDefinition(
            name = ToolName("write_file"),
            description = "Write a text file in the workspace.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string" },
                        "content": { "type": "string" }
                      },
                      "required": ["path", "content"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.WORKSPACE_WRITE,
        )

    val EDIT_FILE =
        ToolDefinition(
            name = ToolName("edit_file"),
            description = "Replace text in a workspace file.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string" },
                        "old_string": { "type": "string" },
                        "new_string": { "type": "string" },
                        "replace_all": { "type": "boolean" }
                      },
                      "required": ["path", "old_string", "new_string"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.WORKSPACE_WRITE,
        )

    val BASH =
        ToolDefinition(
            name = ToolName("bash"),
            description = "Execute a shell command in the current workspace.",
            inputSchema =
                schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "command": { "type": "string" },
                        "timeout": { "type": "integer", "minimum": 1 },
                        "description": { "type": "string" }
                      },
                      "required": ["command"],
                      "additionalProperties": false
                    }
                    """,
                ),
            tier = PermissionTier.DANGER_FULL_ACCESS,
        )

    /** In Clawcode's order, which is also the order they are offered to the model. */
    val ALL: List<ToolDefinition> = listOf(BASH, READ_FILE, WRITE_FILE, EDIT_FILE, GLOB_SEARCH, GREP_SEARCH)

    private val BY_NAME = ALL.associateBy { it.name }

    fun byName(name: ToolName): ToolDefinition? = BY_NAME[name]

    private fun schema(json: String): JsonText = JsonText(json.trimIndent())
}
