package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.tool.ToolInvocation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `read_file`, ported from Clawcode's `read_file_with_selector` without the selector: `offset` is
 * a 0-based line index, `limit` a line count, and the selected lines are joined with `\n`, without
 * line numbers. The result is Clawcode's `ReadFileOutput`; `truncationFooter` is left out, as
 * Clawcode leaves it out when no selector is given.
 */
internal class ReadFileTool(private val guard: PathGuard) {
    fun run(invocation: ToolInvocation.ReadFile): String {
        val file = guard.existing(invocation.path)
        val lines = TextFiles.lines(TextFiles.readChecked(file))
        val start = (invocation.offset ?: 0).coerceIn(0, lines.size)
        val end = invocation.limit?.let { minOf(start.toLong() + it.coerceAtLeast(0), lines.size.toLong()).toInt() } ?: lines.size
        return ToolJson.pretty(
            buildJsonObject {
                put("type", "text")
                putJsonObject("file") {
                    put("filePath", file.toString())
                    put("content", lines.subList(start, end).joinToString("\n"))
                    put("numLines", end - start)
                    put("startLine", start + 1)
                    put("totalLines", lines.size)
                }
            },
        )
    }
}
