package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.session.ContentBlock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A tool call and its result as terminal lines, ported from Clawcode's `format_tool_call_start`,
 * `format_tool_result` and their helpers (`rusty-claude-cli/src/main.rs`, commit 22fe360): the
 * same frame, words, colours, `✓`/`✗` and display caps. Pure, so every format is testable
 * without a terminal.
 *
 * Departures from Clawcode: no emoji (their width differs between terminals and breaks the
 * frame); only Butler's own tool names get a tailored form, not Clawcode's aliases (`Read`,
 * `Bash`, ...), which would catch an MCP tool of that name; and a built-in tool whose output is
 * not a JSON object (a tool not yet built, such as bash before lane F) gets the generic form,
 * showing its raw text, instead of an envelope filled with placeholders.
 */
class ToolCallRenderer(private val style: TerminalStyle = TerminalStyle.PLAIN) {
    /** The frame shown when a call starts: the tool name, then what it acts on. */
    fun started(call: ContentBlock.ToolUse): List<String> {
        val name = call.name.value
        val input = parse(call.input.value)
        val detail =
            when (name) {
                BASH -> bashCall(input)
                READ_FILE -> style.paint("Reading ${toolPath(input)}…", DIM)
                WRITE_FILE -> {
                    val lines = lineCount(input.field("content").string())
                    style.paint("Writing ${toolPath(input)}", WRITE) + " " + style.paint("($lines lines)", DIM)
                }
                EDIT_FILE -> {
                    val old = (input.field("old_string") ?: input.field("oldString")).string().orEmpty()
                    val new = (input.field("new_string") ?: input.field("newString")).string().orEmpty()
                    style.paint("Editing ${toolPath(input)}", EDIT) + patchPreview(old, new)?.let { "\n$it" }.orEmpty()
                }
                GLOB_SEARCH -> searchStart("Glob", input)
                GREP_SEARCH -> searchStart("Grep", input)
                else -> summarizePayload(call.input.value)
            }
        val border = "─".repeat(name.length + 8)
        val block =
            style.paint("╭─ ", FRAME) + style.paint(name, TOOL_NAME) + style.paint(" ─╮", FRAME) + "\n" +
                style.paint("│", FRAME) + " " + detail + "\n" +
                style.paint("╰$border╯", FRAME)
        return block.split('\n')
    }

    /** The lines shown when a call has finished: `✓` or `✗`, the tool name and a short view of its output. */
    fun finished(call: ContentBlock.ToolUse, result: ContentBlock.ToolResult): List<String> {
        val name = call.name.value
        if (result.isError) {
            val icon = style.paint("✗", FAILED)
            val summary = truncateForSummary(result.output.trim(), ERROR_SUMMARY_CHARS)
            val head = "$icon ${style.paint(name, LABEL)}"
            return if (summary.isEmpty()) listOf(head) else listOf(head) + style.paint(summary, ERROR_TEXT).split('\n')
        }
        val icon = style.paint("✓", OK)
        val output = parse(result.output)
        val rendered =
            if (output !is JsonObject) {
                genericResult(icon, name, output)
            } else {
                when (name) {
                    BASH -> bashResult(icon, output)
                    READ_FILE -> readResult(icon, output)
                    WRITE_FILE -> writeResult(icon, output, parse(call.input.value))
                    EDIT_FILE -> editResult(icon, output)
                    GLOB_SEARCH -> globResult(icon, output)
                    GREP_SEARCH -> grepResult(icon, output)
                    else -> genericResult(icon, name, output)
                }
            }
        return rendered.split('\n')
    }

    private fun bashCall(input: JsonElement): String {
        val command = input.field("command").string().orEmpty()
        return if (command.isEmpty()) "" else style.paint(" $ ${truncateForSummary(command, COMMAND_SUMMARY_CHARS)} ", COMMAND)
    }

    private fun searchStart(label: String, input: JsonElement): String {
        val pattern = input.field("pattern").string() ?: "?"
        val scope = input.field("path").string() ?: "."
        return "$label $pattern\n" + style.paint("in $scope", DIM)
    }

    private fun patchPreview(old: String, new: String): String? {
        if (old.isEmpty() && new.isEmpty()) return null
        return style.paint("- ${truncateForSummary(firstVisibleLine(old), PATCH_SUMMARY_CHARS)}", REMOVED) + "\n" +
            style.paint("+ ${truncateForSummary(firstVisibleLine(new), PATCH_SUMMARY_CHARS)}", ADDED)
    }

    private fun bashResult(icon: String, output: JsonObject): String {
        var head = "$icon ${style.paint("bash", LABEL)}"
        val taskId = output.field("backgroundTaskId").string()
        val status = output.field("returnCodeInterpretation").string()?.takeIf { it.isNotEmpty() }
        if (taskId != null) {
            head += " backgrounded ($taskId)"
        } else if (status != null) {
            head += " $status"
        }
        val parts = mutableListOf(head)
        output.field("stdout").string()?.takeIf { it.isNotBlank() }?.let {
            parts += truncateForDisplay(it, TOOL_OUTPUT_DISPLAY_MAX_LINES, TOOL_OUTPUT_DISPLAY_MAX_CHARS)
        }
        output.field("stderr").string()?.takeIf { it.isNotBlank() }?.let {
            parts += style.paint(truncateForDisplay(it, TOOL_OUTPUT_DISPLAY_MAX_LINES, TOOL_OUTPUT_DISPLAY_MAX_CHARS), ERROR_TEXT)
        }
        return parts.joinToString("\n\n")
    }

    private fun readResult(icon: String, output: JsonObject): String {
        val file = output.field("file") ?: output
        val path = toolPath(file)
        val startLine = file.field("startLine").count() ?: 1L
        val numLines = file.field("numLines").count() ?: 0L
        val totalLines = file.field("totalLines").count() ?: numLines
        val content = file.field("content").string().orEmpty()
        val endLine = startLine + (numLines - 1).coerceAtLeast(0L)
        val head = "$icon " + style.paint("Read $path (lines $startLine-${maxOf(endLine, startLine)} of $totalLines)", DIM)
        // Clawcode always adds the content line; an empty read would then print a blank line.
        val preview = truncateForDisplay(content, READ_DISPLAY_MAX_LINES, READ_DISPLAY_MAX_CHARS)
        return if (preview.isEmpty()) head else "$head\n$preview"
    }

    /**
     * The line count comes from the envelope's `content`, as in Clawcode, or from the call's input
     * when the envelope leaves the file out (lane C proposes not echoing it back to the model).
     */
    private fun writeResult(icon: String, output: JsonObject, input: JsonElement): String {
        val path = toolPath(output)
        val kind = output.field("type").string() ?: "write"
        val lines = lineCount(output.field("content").string() ?: input.field("content").string())
        val verb = if (kind == "create") "Wrote" else "Updated"
        return "$icon " + style.paint("$verb $path", WRITE) + " " + style.paint("($lines lines)", DIM)
    }

    private fun editResult(icon: String, output: JsonObject): String {
        val path = toolPath(output)
        val suffix = if (output.field("replaceAll").flag() == true) " (replace all)" else ""
        val preview =
            structuredPatchPreview(output)
                ?: patchPreview(output.field("oldString").string().orEmpty(), output.field("newString").string().orEmpty())
        val head = "$icon " + style.paint("Edited $path$suffix", EDIT)
        return if (preview == null) head else "$head\n$preview"
    }

    /** Up to [PATCH_PREVIEW_HUNKS] hunks of [PATCH_PREVIEW_LINES] lines; null, as in Clawcode, when any shown hunk has no lines. */
    private fun structuredPatchPreview(output: JsonObject): String? {
        val hunks = output.field("structuredPatch") as? JsonArray ?: return null
        val preview = mutableListOf<String>()
        for (hunk in hunks.take(PATCH_PREVIEW_HUNKS)) {
            val lines = hunk.field("lines") as? JsonArray ?: return null
            lines.mapNotNull { it.string() }.take(PATCH_PREVIEW_LINES).forEach { line ->
                preview +=
                    when (line.firstOrNull()) {
                        '+' -> style.paint(line, ADDED)
                        '-' -> style.paint(line, REMOVED)
                        else -> line
                    }
            }
        }
        return preview.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun globResult(icon: String, output: JsonObject): String {
        val numFiles = output.field("numFiles").count() ?: 0L
        val filenames = filenames(output)
        val head = "$icon ${style.paint(GLOB_SEARCH, LABEL)} matched $numFiles files"
        return if (filenames.isEmpty()) head else "$head\n$filenames"
    }

    private fun grepResult(icon: String, output: JsonObject): String {
        val numMatches = output.field("numMatches").count() ?: 0L
        val numFiles = output.field("numFiles").count() ?: 0L
        val content = output.field("content").string().orEmpty()
        val filenames = filenames(output)
        val summary = "$icon ${style.paint(GREP_SEARCH, LABEL)} $numMatches matches across $numFiles files"
        return when {
            content.isNotBlank() -> "$summary\n${truncateForDisplay(content, TOOL_OUTPUT_DISPLAY_MAX_LINES, TOOL_OUTPUT_DISPLAY_MAX_CHARS)}"
            filenames.isNotEmpty() -> "$summary\n$filenames"
            else -> summary
        }
    }

    private fun genericResult(icon: String, name: String, output: JsonElement): String {
        val rendered =
            when (output) {
                is JsonNull -> ""
                is JsonPrimitive -> if (output.isString) output.content else output.toString()
                is JsonObject, is JsonArray -> PRETTY_JSON.encodeToString(JsonElement.serializer(), output)
            }
        val preview = truncateForDisplay(rendered, TOOL_OUTPUT_DISPLAY_MAX_LINES, TOOL_OUTPUT_DISPLAY_MAX_CHARS)
        return when {
            preview.isEmpty() -> "$icon ${style.paint(name, LABEL)}"
            preview.contains('\n') -> "$icon ${style.paint(name, LABEL)}\n$preview"
            else -> "$icon ${style.paint("$name:", LABEL)} $preview"
        }
    }

    private fun filenames(output: JsonObject): String =
        (output.field("filenames") as? JsonArray)
            ?.mapNotNull { it.string() }
            ?.take(FILENAMES_SHOWN)
            ?.joinToString("\n")
            .orEmpty()

    /** Clawcode's `truncate_output_for_display`: at most [maxLines] lines and [maxChars] characters, then the notice. */
    private fun truncateForDisplay(content: String, maxLines: Int, maxChars: Int): String {
        val original = content.trimEnd('\n')
        if (original.isEmpty()) return ""
        val kept = mutableListOf<String>()
        var used = 0
        var truncated = false
        for ((index, line) in lines(original).withIndex()) {
            if (index >= maxLines) {
                truncated = true
                break
            }
            val newline = if (kept.isEmpty()) 0 else 1
            val available = (maxChars - (used + newline)).coerceAtLeast(0)
            if (available == 0) {
                truncated = true
                break
            }
            val length = line.codePointCount(0, line.length)
            if (length > available) {
                kept += line.substring(0, line.offsetByCodePoints(0, available))
                truncated = true
                break
            }
            kept += line
            used += newline + length
        }
        val preview = kept.joinToString("\n")
        if (!truncated) return preview
        return (if (preview.isEmpty()) "" else "$preview\n") + style.paint(DISPLAY_TRUNCATION_NOTICE, DIM)
    }

    /** Clawcode's `summarize_tool_payload`: compact JSON, or the trimmed text, cut short. */
    private fun summarizePayload(payload: String): String {
        val compact = runCatching { Json.parseToJsonElement(payload).toString() }.getOrElse { payload.trim() }
        return truncateForSummary(compact, PAYLOAD_SUMMARY_CHARS)
    }

    private fun toolPath(element: JsonElement): String =
        (element.field("file_path") ?: element.field("filePath") ?: element.field("path")).string() ?: "?"

    /**
     * serde_json, which Clawcode uses, reads a non-JSON output as plain text. kotlinx also accepts a
     * bare word as a literal, so anything serde would reject is kept as the original text.
     */
    private fun parse(text: String): JsonElement {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val strict =
            when (element) {
                null -> false
                is JsonObject, is JsonArray, is JsonNull -> true
                is JsonPrimitive -> element.isString || element.booleanOrNull != null || element.doubleOrNull != null
            }
        return if (strict && element != null) element else JsonPrimitive(text)
    }

    private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A non-negative whole number, as serde's `as_u64` reads it. */
    private fun JsonElement?.count(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

    private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** Rust's `str::lines`: split on `\n`, drop a trailing `\r` and no empty line after a final newline. */
    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return (if (text.endsWith('\n')) parts.dropLast(1) else parts).map { it.removeSuffix("\r") }
    }

    private fun lineCount(text: String?): Int = lines(text.orEmpty()).size

    private fun firstVisibleLine(text: String): String = lines(text).firstOrNull { it.isNotBlank() } ?: text

    /** Clawcode's `truncate_for_summary`: the first [limit] characters, and `…` when there were more. */
    private fun truncateForSummary(value: String, limit: Int): String =
        if (value.codePointCount(0, value.length) <= limit) value else value.substring(0, value.offsetByCodePoints(0, limit)) + "…"

    private companion object {
        const val BASH = "bash"
        const val READ_FILE = "read_file"
        const val WRITE_FILE = "write_file"
        const val EDIT_FILE = "edit_file"
        const val GLOB_SEARCH = "glob_search"
        const val GREP_SEARCH = "grep_search"

        // Clawcode's display caps; the full output stays in the session.
        const val READ_DISPLAY_MAX_LINES = 80
        const val READ_DISPLAY_MAX_CHARS = 6_000
        const val TOOL_OUTPUT_DISPLAY_MAX_LINES = 60
        const val TOOL_OUTPUT_DISPLAY_MAX_CHARS = 4_000
        const val DISPLAY_TRUNCATION_NOTICE = "… output truncated for display; full result preserved in session."
        const val ERROR_SUMMARY_CHARS = 160
        const val COMMAND_SUMMARY_CHARS = 160
        const val PATCH_SUMMARY_CHARS = 72
        const val PAYLOAD_SUMMARY_CHARS = 96
        const val PATCH_PREVIEW_HUNKS = 2
        const val PATCH_PREVIEW_LINES = 6
        const val FILENAMES_SHOWN = 8

        // Clawcode's SGR codes.
        const val FRAME = "38;5;245"
        const val LABEL = "38;5;245"
        const val TOOL_NAME = "1;36"
        const val DIM = "2"
        const val WRITE = "1;32"
        const val EDIT = "1;33"
        const val ADDED = "38;5;70"
        const val REMOVED = "38;5;203"
        const val ERROR_TEXT = "38;5;203"
        const val COMMAND = "48;5;236;38;5;255"
        const val OK = "1;32"
        const val FAILED = "1;31"

        /** serde_json's `to_string_pretty` layout: two-space indent. */
        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY_JSON =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
    }
}
