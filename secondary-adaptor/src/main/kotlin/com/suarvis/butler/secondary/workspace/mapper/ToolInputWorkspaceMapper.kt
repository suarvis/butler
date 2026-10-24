package com.suarvis.butler.secondary.workspace.mapper

import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration

/**
 * A built-in tool's JSON input → typed [ToolInvocation], held to that tool's [BuiltInTools] schema:
 * the same field names and types, required fields present and the schema's minimums. Fields the
 * schema does not list are ignored, and an explicit `null` counts as left out, as both do for
 * Clawcode's serde input structs, so a model that adds an extra key still gets its call run. Paths go
 * through [WorkspacePath.within], so nothing after decoding sees a path outside the workspace.
 *
 * The input is walked as a [JsonObject] rather than decoded into DTOs, so every refusal can name
 * the field that is wrong and what the schema expects instead; the model is told exactly what to fix.
 */
object ToolInputWorkspaceMapper {
    /** Field names per tool, read from the schemas themselves so decoding and schema cannot drift apart. */
    private val fieldNames: Map<ToolName, Set<String>> =
        BuiltInTools.ALL.associate { tool ->
            tool.name to Json.parseToJsonElement(tool.inputSchema.value).jsonObject.getValue("properties").jsonObject.keys
        }

    /** @throws InvalidToolInputException naming the field that is wrong */
    fun toInvocation(workspace: WorkspaceRoot, tool: ToolName, input: JsonText): ToolInvocation {
        val names = requireNotNull(fieldNames[tool]) { "$tool is not a built-in tool" }
        val fields = Fields(tool, workspace, parseObject(tool, input), names)
        return when (tool) {
            BuiltInTools.READ_FILE.name -> readFile(fields)
            BuiltInTools.GLOB_SEARCH.name -> globSearch(fields)
            BuiltInTools.GREP_SEARCH.name -> grepSearch(fields)
            BuiltInTools.WRITE_FILE.name -> writeFile(fields)
            BuiltInTools.EDIT_FILE.name -> editFile(fields)
            BuiltInTools.BASH.name -> bash(fields)
            else -> error("No decoder for built-in tool $tool")
        }
    }

    private fun readFile(input: Fields) =
        ToolInvocation.ReadFile(
            path = input.requiredPath("path"),
            offset = input.optionalInt("offset", minimum = 0),
            limit = input.optionalInt("limit", minimum = 1),
        )

    private fun globSearch(input: Fields) =
        ToolInvocation.GlobSearch(
            pattern = input.requiredString("pattern"),
            under = input.optionalPath("path"),
        )

    private fun grepSearch(input: Fields) =
        ToolInvocation.GrepSearch(
            pattern = input.requiredString("pattern"),
            under = input.optionalPath("path"),
            glob = input.optionalString("glob"),
            outputMode = input.optionalString("output_mode"),
            before = input.optionalInt("-B", minimum = 0),
            after = input.optionalInt("-A", minimum = 0),
            // Both spellings are checked; `context` wins when both are sent, as in Clawcode.
            context = input.optionalInt("-C", minimum = 0).let { short -> input.optionalInt("context", minimum = 0) ?: short },
            lineNumbers = input.optionalBoolean("-n"),
            ignoreCase = input.optionalBoolean("-i"),
            fileType = input.optionalString("type"),
            headLimit = input.optionalInt("head_limit", minimum = 1),
            offset = input.optionalInt("offset", minimum = 0),
            multiline = input.optionalBoolean("multiline"),
        )

    private fun writeFile(input: Fields) =
        ToolInvocation.WriteFile(
            path = input.requiredPath("path"),
            content = input.requiredString("content"),
        )

    private fun editFile(input: Fields) =
        ToolInvocation.EditFile(
            path = input.requiredPath("path"),
            oldText = input.requiredString("old_string"),
            newText = input.requiredString("new_string"),
            replaceAll = input.optionalBoolean("replace_all") ?: false,
        )

    /** `timeout` is in milliseconds, as in Clawcode, and may exceed an [Int]. */
    private fun bash(input: Fields) =
        ToolInvocation.Bash(
            command = input.requiredString("command"),
            timeout = input.optionalLong("timeout", minimum = 1)?.let(Duration::ofMillis),
            description = input.optionalString("description"),
        )

    /**
     * The string a permission rule's argument is matched against for an MCP call, as Clawcode's
     * `extract_permission_subject` picks it: the first top-level string among [SUBJECT_KEYS]. Null
     * when the input is not a JSON object or has none of them; core then matches the whole input.
     */
    fun mcpSubject(input: JsonText): String? {
        val fields =
            try {
                Json.parseToJsonElement(input.value) as? JsonObject
            } catch (malformed: SerializationException) {
                null
            } ?: return null
        return SUBJECT_KEYS.firstNotNullOfOrNull { key -> (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
    }

    private val SUBJECT_KEYS =
        listOf("command", "path", "file_path", "filePath", "notebook_path", "notebookPath", "url", "pattern", "code", "message")

    private fun parseObject(tool: ToolName, input: JsonText): JsonObject {
        val element =
            try {
                Json.parseToJsonElement(input.value)
            } catch (malformed: SerializationException) {
                throw InvalidToolInputException(tool, "input is not valid JSON")
            }
        return element as? JsonObject ?: throw InvalidToolInputException(tool, "input must be a JSON object")
    }

    /** Typed reads of one tool's input; each failure names the field and what the schema expects. */
    private class Fields(
        private val tool: ToolName,
        private val workspace: WorkspaceRoot,
        private val input: JsonObject,
        private val names: Set<String>,
    ) {
        fun requiredString(field: String): String = string(field, required(field))

        fun optionalString(field: String): String? = optional(field)?.let { string(field, it) }

        fun optionalBoolean(field: String): Boolean? = optional(field)?.let { boolean(field, it) }

        fun optionalInt(field: String, minimum: Int): Int? =
            optional(field)?.let { integer(field, it, minimum.toLong(), Int.MAX_VALUE.toLong()).toInt() }

        fun optionalLong(field: String, minimum: Long): Long? = optional(field)?.let { integer(field, it, minimum, Long.MAX_VALUE) }

        fun requiredPath(field: String): WorkspacePath = path(field, requiredString(field))

        fun optionalPath(field: String): WorkspacePath? = optionalString(field)?.let { path(field, it) }

        private fun required(field: String): JsonElement = optional(field) ?: fail("$field is required")

        private fun optional(field: String): JsonElement? {
            check(field in names) { "The $tool schema has no field '$field'" }
            return input[field]?.takeUnless { it is JsonNull }
        }

        private fun string(field: String, element: JsonElement): String =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: fail("$field must be a string, got ${describe(element)}")

        private fun boolean(field: String, element: JsonElement): Boolean =
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: fail("$field must be a boolean, got ${describe(element)}")

        private fun integer(field: String, element: JsonElement, minimum: Long, maximum: Long): Long {
            val number =
                (element as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigIntegerOrNull()
                    ?: fail("$field must be an integer, got ${describe(element)}")
            if (number < minimum.toBigInteger()) fail("$field must be at least $minimum, got $number")
            if (number > maximum.toBigInteger()) fail("$field must be at most $maximum, got $number")
            return number.toLong()
        }

        private fun path(field: String, raw: String): WorkspacePath {
            if (raw.isBlank()) fail("$field must not be blank")
            if ('\u0000' in raw) fail("$field must not contain a NUL character")
            return WorkspacePath.within(workspace, raw) ?: fail("$field '$raw' is outside the workspace")
        }

        private fun describe(element: JsonElement): String =
            when {
                element is JsonObject -> "an object"
                element is JsonArray -> "an array"
                element is JsonPrimitive && element.isString -> "a string"
                else -> element.toString()
            }

        private fun fail(reason: String): Nothing = throw InvalidToolInputException(tool, reason)
    }
}
