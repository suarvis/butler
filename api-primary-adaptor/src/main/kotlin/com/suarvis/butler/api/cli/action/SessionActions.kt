package com.suarvis.butler.api.cli.action

import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.dto.SessionDeleteOutput
import com.suarvis.butler.api.cli.exception.CliOutputPathException
import com.suarvis.butler.api.cli.mapper.SessionCliMapper
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.SessionMarkdownRenderer
import com.suarvis.butler.api.cli.render.SessionTextRenderer
import com.suarvis.butler.api.cli.dto.ExportOutput
import com.suarvis.butler.api.cli.dto.RestoredOutput
import com.suarvis.butler.api.cli.dto.SessionExistsOutput
import com.suarvis.butler.api.cli.dto.SessionForkOutput
import com.suarvis.butler.api.cli.dto.SessionListOutput
import com.suarvis.butler.api.cli.dto.SessionSwitchOutput
import com.suarvis.butler.api.cli.dto.StatusOutput
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.usecase.session.DeleteSessionUseCase
import com.suarvis.butler.core.usecase.session.ForkSessionUseCase
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import com.suarvis.butler.core.usecase.session.ListSessionsUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Session behaviour shared by picocli subcommands (`butler session list`) and slash commands
 * (`butler --resume latest /session list`, later the REPL). Plays the controller role: call a use
 * case, map the result to a DTO, write it. Never returns domain objects to the console.
 */
@Component
class SessionActions(
    private val getSession: GetSessionUseCase,
    private val listSessions: ListSessionsUseCase,
    private val forkSession: ForkSessionUseCase,
    private val deleteSession: DeleteSessionUseCase,
    private val output: CliOutputWriter,
    @param:Value("\${butler.persistence.store:file}") private val persistenceStore: String,
) {
    fun load(invocation: CliInvocation, reference: SessionReference): Session = getSession.execute(invocation.workspace, reference)

    fun restored(invocation: CliInvocation, session: Session) {
        val payload = SessionCliMapper.toRestoredOutput(session)
        output.success(invocation.format, RestoredOutput.serializer(), payload) { SessionTextRenderer.restored(payload) }
    }

    /** Workspace status plus the given session, or the latest one when [session] is null. */
    fun status(invocation: CliInvocation, session: Session?) {
        val shown = session ?: getSession.findOrNull(invocation.workspace, SessionReference.Latest)
        val payload =
            SessionCliMapper.toStatusOutput(invocation.workspace, persistenceStore, listSessions.count(invocation.workspace), shown)
        output.success(invocation.format, StatusOutput.serializer(), payload) { SessionTextRenderer.status(payload) }
    }

    fun list(invocation: CliInvocation, page: PageRequest) {
        val payload = SessionCliMapper.toListOutput(invocation.workspace, listSessions.execute(invocation.workspace, page))
        output.success(invocation.format, SessionListOutput.serializer(), payload) { SessionTextRenderer.list(payload) }
    }

    fun exists(invocation: CliInvocation, rawTarget: String) {
        val session = getSession.findOrNull(invocation.workspace, SessionReference.parse(rawTarget))
        val payload = SessionCliMapper.toExistsOutput(rawTarget, session)
        output.success(invocation.format, SessionExistsOutput.serializer(), payload) { SessionTextRenderer.exists(payload) }
    }

    fun fork(invocation: CliInvocation, source: SessionReference, branchName: BranchName?): Session {
        val forked = forkSession.execute(invocation.workspace, source, branchName)
        val payload = SessionCliMapper.toForkOutput(forked)
        output.success(invocation.format, SessionForkOutput.serializer(), payload) { SessionTextRenderer.fork(payload) }
        return forked
    }

    fun switchTo(invocation: CliInvocation, reference: SessionReference): Session {
        val session = load(invocation, reference)
        val payload = SessionCliMapper.toSwitchOutput(session)
        output.success(invocation.format, SessionSwitchOutput.serializer(), payload) { SessionTextRenderer.switched(payload) }
        return session
    }

    fun delete(invocation: CliInvocation, reference: SessionReference) {
        val deleted = deleteSession.execute(invocation.workspace, reference)
        val payload = SessionDeleteOutput(sessionId = deleted.value)
        output.success(invocation.format, SessionDeleteOutput.serializer(), payload) { SessionTextRenderer.deleted(payload) }
    }

    /** Writes Markdown to [outputPath] (relative to the workspace) or, when null, to stdout. */
    fun export(invocation: CliInvocation, session: Session, outputPath: String?) {
        val markdown = SessionMarkdownRenderer.render(session)
        val written = outputPath?.let { write(invocation.workspace.path.resolve(it).normalize(), markdown) }
        val payload =
            SessionCliMapper.toExportOutput(session, written?.toString(), markdown.takeIf { written == null })
        output.success(invocation.format, ExportOutput.serializer(), payload) { SessionTextRenderer.exported(payload) }
    }

    private fun write(target: Path, content: String): Path =
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, content)
        } catch (error: IOException) {
            throw CliOutputPathException(target, error)
        }
}
