package com.suarvis.butler.api.cli.render

import com.suarvis.butler.api.cli.dto.ExportOutput
import com.suarvis.butler.api.cli.dto.RestoredOutput
import com.suarvis.butler.api.cli.dto.SessionDeleteOutput
import com.suarvis.butler.api.cli.dto.SessionExistsOutput
import com.suarvis.butler.api.cli.dto.SessionForkOutput
import com.suarvis.butler.api.cli.dto.SessionListOutput
import com.suarvis.butler.api.cli.dto.SessionSwitchOutput
import com.suarvis.butler.api.cli.dto.StatusOutput
import com.suarvis.butler.api.cli.dto.UsageOutput
import com.suarvis.butler.api.cli.dto.VersionOutput
import java.time.Instant

/** Human-readable text in Clawcode's "label column + value" style. Renders DTOs, never domain objects. */
object SessionTextRenderer {
    fun status(output: StatusOutput): String =
        buildString {
            appendLine("Status")
            row("Workspace", output.workspace.path)
            row("Store", output.workspace.persistenceStore)
            row("Sessions", output.workspace.sessionCount)
            val session = output.session
            if (session == null) {
                row("Session", "none yet")
                return@buildString
            }
            appendLine()
            appendLine("Session")
            row("Id", session.id)
            row("Model", session.model ?: "default")
            row("Messages", session.messages)
            row("Turns", session.turns)
            row("Pending tools", session.pendingToolCalls)
            row("Updated", Instant.ofEpochMilli(session.updatedAtMs))
            session.parentSessionId?.let { row("Forked from", lineage(it, session.branchName)) }
            appendLine()
            appendLine("Usage")
            row("Latest total", session.latestUsage?.totalTokens ?: 0)
            usageRows(session.cumulativeUsage)
        }.trimEnd()

    fun list(output: SessionListOutput): String =
        buildString {
            appendLine("Sessions")
            row("Workspace", output.workspace)
            if (output.sessionDetails.isEmpty()) {
                append("  No sessions saved yet.")
                return@buildString
            }
            output.sessionDetails.forEach { session ->
                val lineage = session.parentSessionId?.let { " from=$it" }.orEmpty() + session.branchName?.let { " branch=$it" }.orEmpty()
                appendLine(
                    "  %-30s msgs=%-4d modified=%s%s".format(
                        session.id,
                        session.messageCount,
                        Instant.ofEpochMilli(session.updatedAtMs),
                        lineage,
                    ),
                )
            }
            val page = output.page
            append("  Page ${page.page} · ${output.sessionDetails.size} of ${page.totalItems}")
            if (page.hasNext) append(" · next: --page ${page.page + 1}")
        }

    fun exists(output: SessionExistsOutput): String =
        if (output.exists) "Session ${output.sessionId} exists" else "No session matches '${output.target}'"

    fun fork(output: SessionForkOutput): String =
        buildString {
            appendLine("Session forked")
            row("Id", output.sessionId)
            row("Forked from", lineage(output.parentSessionId, output.branchName))
            row("Messages", output.messageCount)
        }.trimEnd()

    fun switched(output: SessionSwitchOutput): String = "Switched to session ${output.sessionId} (${output.messageCount} messages)"

    fun deleted(output: SessionDeleteOutput): String = "Deleted session ${output.sessionId}"

    fun restored(output: RestoredOutput): String = output.message

    fun exported(output: ExportOutput): String =
        output.markdown ?: "Exported session ${output.sessionId} (${output.messageCount} messages) to ${output.outputPath}"

    fun version(output: VersionOutput): String =
        buildString {
            appendLine("Butler")
            row("Version", output.version)
            row("Java", output.javaVersion)
        }.trimEnd()

    private fun StringBuilder.usageRows(usage: UsageOutput) {
        row("Cumulative input", usage.inputTokens)
        row("Cumulative output", usage.outputTokens)
        row("Cache create", usage.cacheCreationInputTokens)
        row("Cache read", usage.cacheReadInputTokens)
        row("Cumulative total", usage.totalTokens)
    }

    private fun StringBuilder.row(label: String, value: Any) {
        appendLine("  %-17s %s".format(label, value))
    }

    private fun lineage(parent: String, branch: String?): String = parent + branch?.let { " (branch $it)" }.orEmpty()
}
