package com.suarvis.butler.api.cli.mapper

import com.suarvis.butler.api.cli.dto.ExportOutput
import com.suarvis.butler.api.cli.dto.PageOutput
import com.suarvis.butler.api.cli.dto.RestoredOutput
import com.suarvis.butler.api.cli.dto.SessionDetailOutput
import com.suarvis.butler.api.cli.dto.SessionExistsOutput
import com.suarvis.butler.api.cli.dto.SessionForkOutput
import com.suarvis.butler.api.cli.dto.SessionListOutput
import com.suarvis.butler.api.cli.dto.SessionStatusOutput
import com.suarvis.butler.api.cli.dto.SessionSwitchOutput
import com.suarvis.butler.api.cli.dto.StatusOutput
import com.suarvis.butler.api.cli.dto.UsageOutput
import com.suarvis.butler.api.cli.dto.WorkspaceOutput
import com.suarvis.butler.core.domain.generic.Page
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionSummary

/** Domain ↔ CLI output DTOs, plus CLI paging (1-based page + size) ↔ gateway offset/limit. */
object SessionCliMapper {
    fun toPageRequest(page: Int, size: Int): PageRequest = PageRequest(offset = (page - 1).coerceAtLeast(0) * size, limit = size)

    fun toListOutput(workspace: WorkspaceRoot, page: Page<SessionSummary>): SessionListOutput =
        SessionListOutput(
            workspace = workspace.toString(),
            sessions = page.items.map { it.id.value },
            sessionDetails = page.items.map(::toDetail),
            page =
                PageOutput(
                    page = page.request.offset / page.request.limit + 1,
                    size = page.request.limit,
                    totalItems = page.totalItems,
                    hasNext = page.hasNext,
                ),
        )

    fun toStatusOutput(workspace: WorkspaceRoot, persistenceStore: String, sessionCount: Long, session: Session?): StatusOutput =
        StatusOutput(
            workspace = WorkspaceOutput(workspace.toString(), persistenceStore, sessionCount),
            session = session?.let(::toSessionStatus),
        )

    fun toExistsOutput(target: String, session: Session?): SessionExistsOutput =
        SessionExistsOutput(target = target, exists = session != null, sessionId = session?.id?.value)

    fun toForkOutput(fork: Session): SessionForkOutput {
        val provenance = requireNotNull(fork.fork) { "a forked session records its parent" }
        return SessionForkOutput(
            sessionId = fork.id.value,
            parentSessionId = provenance.parentSessionId.value,
            branchName = provenance.branchName?.value,
            messageCount = fork.messageCount,
        )
    }

    fun toSwitchOutput(session: Session): SessionSwitchOutput = SessionSwitchOutput(sessionId = session.id.value, messageCount = session.messageCount)

    fun toRestoredOutput(session: Session): RestoredOutput =
        RestoredOutput(
            sessionId = session.id.value,
            messageCount = session.messageCount,
            message = "Restored session ${session.id} (${session.messageCount} messages)",
        )

    fun toExportOutput(session: Session, outputPath: String?, markdown: String?): ExportOutput =
        ExportOutput(sessionId = session.id.value, messageCount = session.messageCount, outputPath = outputPath, markdown = markdown)

    fun toUsage(usage: TokenUsage): UsageOutput =
        UsageOutput(usage.inputTokens, usage.outputTokens, usage.cacheCreationInputTokens, usage.cacheReadInputTokens, usage.totalTokens)

    private fun toDetail(summary: SessionSummary): SessionDetailOutput =
        SessionDetailOutput(
            id = summary.id.value,
            model = summary.model?.value,
            messageCount = summary.messageCount,
            createdAtMs = summary.createdAt.toEpochMilli(),
            updatedAtMs = summary.updatedAt.toEpochMilli(),
            parentSessionId = summary.fork?.parentSessionId?.value,
            branchName = summary.fork?.branchName?.value,
        )

    private fun toSessionStatus(session: Session): SessionStatusOutput =
        SessionStatusOutput(
            id = session.id.value,
            model = session.model?.value,
            messages = session.messageCount,
            turns = session.turnCount,
            pendingToolCalls = session.pendingToolCalls().size,
            createdAtMs = session.createdAt.toEpochMilli(),
            updatedAtMs = session.updatedAt.toEpochMilli(),
            parentSessionId = session.fork?.parentSessionId?.value,
            branchName = session.fork?.branchName?.value,
            latestUsage = session.latestUsage()?.let(::toUsage),
            cumulativeUsage = toUsage(session.cumulativeUsage()),
        )
}
