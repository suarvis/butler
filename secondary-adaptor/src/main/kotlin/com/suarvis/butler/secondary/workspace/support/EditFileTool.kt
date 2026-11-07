package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * `edit_file`, ported from Clawcode's `edit_file` and `edit_file_in_workspace`: the file must exist
 * inside the workspace and be UTF-8 text; identical strings and a missing match are refused with
 * Clawcode's words; `old_string` is replaced as raw text, so line endings and the final newline
 * stay as they were. The result is Clawcode's `EditFileOutput`, with `userModified` always false
 * and `gitDiff` null, as in Clawcode.
 *
 * Departures from Clawcode: without `replace_all`, an `old_string` found in more than one place is
 * refused, where Clawcode edits the first and may change the wrong one; the file is replaced
 * atomically with [AtomicWrite], keeping its permissions; and `structuredPatch` holds real diff
 * hunks from [UnifiedDiff].
 */
internal class EditFileTool(private val guard: PathGuard, private val writer: AtomicWrite) {
    /** Edits the file; the outcome's preview is the diff as text. */
    fun run(invocation: ToolInvocation.EditFile): ToolOutcome {
        val edit = plan(invocation)
        val diff = UnifiedDiff.between(edit.original, edit.updated)
        writer.write(edit.file, edit.updated.toByteArray(StandardCharsets.UTF_8))
        val envelope =
            buildJsonObject {
                put("filePath", edit.file.toString())
                put("oldString", invocation.oldText)
                put("newString", invocation.newText)
                put("originalFile", edit.original)
                put("structuredPatch", diff.structuredPatch())
                put("userModified", false)
                put("replaceAll", invocation.replaceAll)
                put("gitDiff", JsonNull)
            }
        return ToolOutcome(ToolJson.pretty(envelope), preview = text(edit, diff))
    }

    /** The diff [run] would report, after the same checks, without writing; it refuses what [run] refuses. */
    fun preview(invocation: ToolInvocation.EditFile): String? {
        val edit = plan(invocation)
        return text(edit, UnifiedDiff.between(edit.original, edit.updated))
    }

    private class Edit(val file: Path, val original: String, val updated: String)

    // Clawcode's order: the file is resolved and read before the strings are looked at.
    private fun plan(invocation: ToolInvocation.EditFile): Edit {
        val file = guard.existing(invocation.path)
        val original = TextFiles.readUtf8(file)
        val old = invocation.oldText
        if (old == invocation.newText) throw WorkspaceToolException("old_string and new_string must differ")
        val matches = occurrences(original, old)
        if (matches.isEmpty()) throw WorkspaceToolException("old_string not found in file")
        if (matches.size > 1 && !invocation.replaceAll) {
            throw WorkspaceToolException("old_string matches ${matches.size} places; include more context or set replace_all")
        }
        val updated =
            buildString {
                var from = 0
                for (at in matches) {
                    append(original, from, at).append(invocation.newText)
                    from = at + old.length
                }
                append(original, from, original.length)
            }
        return Edit(file, original, updated)
    }

    /**
     * Where [needle] starts in [text], left to right without overlaps, as Rust's `str::replace`
     * finds them. An empty needle matches before every character and at the end, never inside a
     * surrogate pair, as Rust matches it at every char boundary.
     */
    private fun occurrences(text: String, needle: String): List<Int> {
        val found = ArrayList<Int>()
        var at = text.indexOf(needle)
        while (at >= 0) {
            found += at
            if (needle.isEmpty() && at == text.length) break
            val next = if (needle.isEmpty()) at + Character.charCount(text.codePointAt(at)) else at + needle.length
            at = text.indexOf(needle, next)
        }
        return found
    }

    private fun text(edit: Edit, diff: UnifiedDiff): String? {
        val label = guard.label(edit.file)
        return diff.text(label, label)
    }
}
