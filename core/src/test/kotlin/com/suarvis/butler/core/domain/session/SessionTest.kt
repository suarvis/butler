package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.exception.TranscriptIntegrityException
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionTest {
    private val start = SessionFixtures.start
    private val readFile =
        ContentBlock.ToolUse(ToolCallId("toolu_1"), ToolName("read_file"), JsonText("""{"path":"a.txt"}"""))
    private val grep = ContentBlock.ToolUse(ToolCallId("toolu_2"), ToolName("grep_search"), JsonText("""{"pattern":"x"}"""))

    private fun sessionAwaitingTools() =
        SessionFixtures.emptySession()
            .appendUserPrompt("look around", start.plusSeconds(1))
            .appendAssistantMessage(ConversationMessage.assistant(listOf(readFile, grep)), start.plusSeconds(2))

    @Test
    fun `a new session is empty and records its model`() {
        val session = SessionFixtures.emptySession()

        assertEquals(0, session.messageCount)
        assertEquals(ModelId("sonnet"), session.model)
        assertEquals(session.createdAt, session.updatedAt)
        assertTrue(session.pendingToolCalls().isEmpty())
    }

    @Test
    fun `appending a prompt adds a user message and prompt history`() {
        val at = start.plusSeconds(5)
        val session = SessionFixtures.emptySession().appendUserPrompt("hello", at)

        assertEquals(MessageRole.USER, session.messages.single().role)
        assertEquals("hello", session.messages.single().text)
        assertEquals(listOf(PromptEntry(at, "hello")), session.promptHistory)
        assertEquals(at, session.updatedAt)
        assertEquals(1, session.turnCount)
    }

    @Test
    fun `blank prompts are rejected`() {
        assertThrows<DomainValidationException> { SessionFixtures.emptySession().appendUserPrompt("  ", start) }
    }

    @Test
    fun `tool calls without results are pending`() {
        assertEquals(listOf(readFile, grep), sessionAwaitingTools().pendingToolCalls())
    }

    @Test
    fun `recording a result answers exactly that call`() {
        val session =
            sessionAwaitingTools()
                .recordToolResult(ContentBlock.ToolResult(readFile.id, readFile.name, "contents", false), start.plusSeconds(3))

        assertEquals(listOf(grep), session.pendingToolCalls())
        assertEquals(MessageRole.TOOL, session.messages.last().role)
    }

    @Test
    fun `a result for an unknown call breaks the transcript`() {
        val stranger = ContentBlock.ToolResult(ToolCallId("toolu_999"), ToolName("bash"), "?", false)

        assertThrows<TranscriptIntegrityException> { sessionAwaitingTools().recordToolResult(stranger, start.plusSeconds(3)) }
    }

    @Test
    fun `no prompt or reply may be added while tool calls are unanswered`() {
        val session = sessionAwaitingTools()

        assertThrows<TranscriptIntegrityException> { session.appendUserPrompt("next", start.plusSeconds(3)) }
        assertThrows<TranscriptIntegrityException> {
            session.appendAssistantMessage(ConversationMessage.assistant(listOf(ContentBlock.Text("done"))), start.plusSeconds(3))
        }
    }

    @Test
    fun `closing dangling calls answers every pending call with an error`() {
        val session = sessionAwaitingTools().closeDanglingToolCalls("Interrupted by user", start.plusSeconds(3))

        assertTrue(session.pendingToolCalls().isEmpty())
        val results = session.messages.takeLast(2).flatMap { it.toolResults }
        assertEquals(listOf(readFile.id, grep.id), results.map { it.toolUseId })
        assertTrue(results.all { it.isError && it.output == "Interrupted by user" })
        session.appendUserPrompt("carry on", start.plusSeconds(4))
    }

    @Test
    fun `cumulative usage sums every message and latest usage is the last one`() {
        val session = SessionFixtures.sessionWithToolRoundTrip()

        assertEquals(TokenUsage(inputTokens = 250, outputTokens = 30, cacheReadInputTokens = 90), session.cumulativeUsage())
        assertEquals(TokenUsage(inputTokens = 150, outputTokens = 10, cacheReadInputTokens = 90), session.latestUsage())
        assertNull(SessionFixtures.emptySession().latestUsage())
    }

    @Test
    fun `a fork copies the transcript under a new id and records its parent`() {
        val parent = SessionFixtures.sessionWithToolRoundTrip()
        val at = start.plusSeconds(60)

        val fork = parent.fork(SessionId("session-2"), BranchName("try-b"), at)

        assertEquals(SessionId("session-2"), fork.id)
        assertEquals(parent.messages, fork.messages)
        assertEquals(SessionFork(parent.id, BranchName("try-b")), fork.fork)
        assertEquals(at, fork.createdAt)
        assertNotEquals(parent, fork)
        assertThrows<DomainValidationException> { parent.fork(parent.id, null, at) }
    }

    @Test
    fun `sessions are immutable snapshots compared by value`() {
        val original = SessionFixtures.emptySession()
        val changed = original.appendUserPrompt("hi", start.plusSeconds(1))

        assertEquals(0, original.messageCount)
        assertEquals(SessionFixtures.emptySession(), original)
        assertNotEquals(original, changed)
    }

    @Test
    fun `message roles constrain their blocks`() {
        assertThrows<DomainValidationException> { ConversationMessage(MessageRole.USER, listOf(readFile)) }
        assertThrows<DomainValidationException> {
            ConversationMessage(MessageRole.ASSISTANT, listOf(ContentBlock.ToolResult(readFile.id, readFile.name, "x", false)))
        }
        assertThrows<DomainValidationException> { ConversationMessage(MessageRole.TOOL, listOf(ContentBlock.Text("x"))) }
        assertThrows<DomainValidationException> { ConversationMessage(MessageRole.USER, emptyList()) }
    }

    @Test
    fun `thinking blocks carry no replay data unless a provider supplies it`() {
        assertNull(ContentBlock.Thinking("t", "sig").replay)
    }
}
