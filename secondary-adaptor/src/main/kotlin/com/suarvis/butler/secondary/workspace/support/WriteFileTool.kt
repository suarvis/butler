package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `write_file`, ported from Clawcode's `write_file` and `write_file_in_workspace`: content over
 * [MAX_WRITE_BYTES] is refused, missing parent folders are created, and the file is replaced. The
 * result is Clawcode's `WriteFileOutput`, `create` when there was no readable text file before and
 * `update` otherwise, as Clawcode decides it with `read_to_string(..).ok()`.
 *
 * Departures from Clawcode: the place the write lands is checked with [PathGuard.forWrite], so
 * parent folders are only ever created inside the workspace and a symlink is written through only
 * when its target is inside; the file is replaced atomically with [AtomicWrite], keeping its
 * permissions; and `structuredPatch` holds real diff hunks from [UnifiedDiff].
 */
internal class WriteFileTool(private val guard: PathGuard, private val writer: AtomicWrite) {
    /** Writes the file; the outcome's preview is the diff as text. */
    fun run(invocation: ToolInvocation.WriteFile): ToolOutcome {
        val change = plan(invocation)
        val diff = UnifiedDiff.between(change.original.orEmpty(), invocation.content)
        Files.createDirectories(change.file.parent)
        writer.write(change.file, change.bytes)
        val envelope =
            buildJsonObject {
                put("type", if (change.original == null) "create" else "update")
                put("filePath", change.file.toString())
                put("content", invocation.content)
                put("structuredPatch", diff.structuredPatch())
                put("originalFile", change.original)
                put("gitDiff", JsonNull)
            }
        return ToolOutcome(ToolJson.pretty(envelope), preview = text(change, diff))
    }

    /** The diff [run] would report, after the same checks, without creating or writing anything; null when nothing changes. */
    fun preview(invocation: ToolInvocation.WriteFile): String? {
        val change = plan(invocation)
        return text(change, UnifiedDiff.between(change.original.orEmpty(), invocation.content))
    }

    private class Change(val file: Path, val bytes: ByteArray, val original: String?)

    // Clawcode's order: the workspace boundary, then the size, then the old text.
    private fun plan(invocation: ToolInvocation.WriteFile): Change {
        val file = guard.forWrite(invocation.path)
        val bytes = invocation.content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) throw WorkspaceToolException("content is too large (${bytes.size} bytes, max $MAX_WRITE_BYTES bytes)")
        // As `fs::write` reports a folder; the move would fail too, but in the JDK's words.
        if (Files.isDirectory(file)) throw WorkspaceToolException(IoErrorMessages.IS_A_DIRECTORY)
        return Change(file, bytes, TextFiles.readUtf8OrNull(file))
    }

    private fun text(change: Change, diff: UnifiedDiff): String? {
        val label = guard.label(change.file)
        return diff.text(if (change.original == null) NO_FILE else label, label)
    }

    companion object {
        /** Clawcode's `MAX_WRITE_SIZE`: content over 10 MB, counted in UTF-8 bytes, is refused. */
        const val MAX_WRITE_BYTES: Int = 10 * 1024 * 1024

        /** What a unified diff names as the old side of a file it creates. */
        private const val NO_FILE = "/dev/null"
    }
}
