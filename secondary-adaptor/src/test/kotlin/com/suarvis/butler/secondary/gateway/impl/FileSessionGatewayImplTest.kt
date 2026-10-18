package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.persistence.file.repository.SessionFileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class FileSessionGatewayImplTest : SessionGatewayContract() {
    @TempDir
    lateinit var directory: Path

    private val repository = SessionFileRepository(".butler/sessions")

    override val workspace: WorkspaceRoot
        get() = WorkspaceRoot.of(directory.resolve("project").also { Files.createDirectories(it) })

    override fun gateway(): SessionGateway = FileSessionGatewayImpl(repository)

    @Test
    fun `reads a session written by Clawcode and skips record types it does not know`() {
        val file = repository.fileFor(workspace.path, "session-1760690000000-0")
        Files.createDirectories(file.parent)
        file.writeText(CLAWCODE_SESSION)

        val session = gateway().findById(workspace, SessionId("session-1760690000000-0"))!!

        assertEquals(ModelId("sonnet"), session.model)
        assertEquals(workspace, session.workspace)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), session.messages.map { it.role })
        assertEquals(TokenUsage(inputTokens = 250, outputTokens = 30, cacheReadInputTokens = 90), session.cumulativeUsage())
        assertEquals(ContentBlock.Text("The README is a heading."), session.messages.last().blocks.single())
        assertEquals(1, session.promptHistory.size)
        assertTrue(session.pendingToolCalls().isEmpty())
    }

    @Test
    fun `derives the creation time from the id when an old file omits it`() {
        val file = repository.fileFor(workspace.path, "session-1700000000000-3")
        Files.createDirectories(file.parent)
        file.writeText("""{"type":"session_meta","version":3,"session_id":"session-1700000000000-3","updated_at_ms":1700000005000}""" + "\n")

        val session = gateway().findById(workspace, SessionId("session-1700000000000-3"))!!

        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), session.createdAt)
    }

    @Test
    fun `writes Clawcode version 3 records with a type discriminator`() {
        val session = SessionFixtures.sessionWithToolRoundTrip(workspaceRoot = workspace)

        gateway().save(session)

        val lines = repository.fileFor(workspace.path, session.id.value).readLines()
        assertTrue(lines.first().startsWith("""{"type":"session_meta","version":3,"session_id":"session-1""""), lines.first())
        assertTrue(lines.any { it.contains(""""type":"tool_use"""") })
        assertTrue(lines.last().startsWith("""{"type":"prompt_history""""), lines.last())
    }

    @Test
    fun `a corrupt file surfaces as a gateway error naming the file`() {
        val file = repository.fileFor(workspace.path, "session-bad")
        Files.createDirectories(file.parent)
        file.writeText("{not json\n")

        val error = assertThrows<GatewayException> { gateway().findById(workspace, SessionId("session-bad")) }

        assertTrue(error.message!!.contains("session-bad.jsonl"), error.message)
    }

    private companion object {
        val CLAWCODE_SESSION =
            """
            {"type":"session_meta","version":3,"session_id":"session-1760690000000-0","created_at_ms":1760690000000,"updated_at_ms":1760690004000,"workspace_root":"/somewhere/else","model":"sonnet"}
            {"type":"message","message":{"role":"user","blocks":[{"type":"text","text":"Explain the README"}]}}
            {"type":"message","message":{"role":"assistant","blocks":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":"{\"path\":\"README.md\"}"}],"usage":{"input_tokens":100,"output_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}
            {"type":"message","message":{"role":"tool","blocks":[{"type":"tool_result","tool_use_id":"toolu_1","tool_name":"read_file","output":"# Demo","is_error":false}]}}
            {"type":"message","message":{"role":"assistant","blocks":[{"type":"text","text":"The README is a heading."}],"usage":{"input_tokens":150,"output_tokens":10,"cache_creation_input_tokens":0,"cache_read_input_tokens":90}}}
            {"type":"prompt_history","timestamp_ms":1760690001000,"text":"Explain the README"}
            {"type":"session_heartbeat","observed_at_ms":1760690004000}
            """.trimIndent() + "\n"
    }
}
