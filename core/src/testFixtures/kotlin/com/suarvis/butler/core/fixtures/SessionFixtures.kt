package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.Session
import java.nio.file.Path
import java.time.Instant

object SessionFixtures {
    val workspace: WorkspaceRoot = WorkspaceRoot.of(Path.of("/work/demo"))
    val start: Instant = Instant.parse("2026-10-17T09:00:00Z")

    fun emptySession(
        id: String = "session-1",
        at: Instant = start,
        workspaceRoot: WorkspaceRoot = workspace,
    ): Session = Session.start(SessionId(id), workspaceRoot, ModelId("sonnet"), at)

    /** A full read-file round trip: prompt, tool call, tool result, final answer with usage. */
    fun sessionWithToolRoundTrip(
        id: String = "session-1",
        at: Instant = start,
        workspaceRoot: WorkspaceRoot = workspace,
    ): Session {
        val call = ContentBlock.ToolUse(ToolCallId("toolu_1"), ToolName("read_file"), JsonText("""{"path":"README.md"}"""))
        return emptySession(id, at, workspaceRoot)
            .appendUserPrompt("Explain the README", at.plusSeconds(1))
            .appendAssistantMessage(
                ConversationMessage.assistant(
                    listOf(ContentBlock.Thinking("Need the file first", "sig-1"), call),
                    TokenUsage(inputTokens = 100, outputTokens = 20),
                ),
                at.plusSeconds(2),
            )
            .recordToolResult(ContentBlock.ToolResult(call.id, call.name, "# Demo", isError = false), at.plusSeconds(3))
            .appendAssistantMessage(
                ConversationMessage.assistant(
                    listOf(ContentBlock.Text("The README is a heading.")),
                    TokenUsage(inputTokens = 150, outputTokens = 10, cacheReadInputTokens = 90),
                ),
                at.plusSeconds(4),
            )
    }
}
