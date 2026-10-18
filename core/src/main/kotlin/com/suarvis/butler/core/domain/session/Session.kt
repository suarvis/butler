package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.exception.TranscriptIntegrityException
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.generic.ensure
import java.time.Instant

/**
 * A conversation and everything needed to resume it.
 *
 * Immutable: every change returns a new snapshot, so a half-finished turn can never leave a
 * shared instance in an invalid state. Equality is structural for the same reason.
 *
 * Central invariant (carried over from Clawcode): every tool call the model makes eventually
 * gets exactly one result, and nothing else may be appended while calls are unanswered.
 * Providers reject transcripts that break this, and resumed sessions depend on it.
 */
class Session private constructor(
    val id: SessionId,
    val workspace: WorkspaceRoot,
    val model: ModelId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<ConversationMessage>,
    val compaction: SessionCompaction?,
    val fork: SessionFork?,
    val promptHistory: List<PromptEntry>,
) {
    init {
        ensure(!updatedAt.isBefore(createdAt)) { "Session $id cannot be updated before it was created" }
    }

    val messageCount: Int
        get() = messages.size

    /** Number of user turns, as Clawcode reports it in `/status`. */
    val turnCount: Int
        get() = messages.count { it.role == MessageRole.USER }

    fun cumulativeUsage(): TokenUsage = messages.mapNotNull { it.usage }.fold(TokenUsage.ZERO, TokenUsage::plus)

    fun latestUsage(): TokenUsage? = messages.lastOrNull { it.usage != null }?.usage

    /** Tool calls from the most recent assistant message that have no result yet. */
    fun pendingToolCalls(): List<ContentBlock.ToolUse> {
        val lastAssistant = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistant < 0) return emptyList()
        val answered = messages.drop(lastAssistant + 1).flatMap { it.toolResults }.map { it.toolUseId }.toSet()
        return messages[lastAssistant].toolUses.filterNot { it.id in answered }
    }

    fun appendUserPrompt(text: String, at: Instant): Session {
        ensure(text.isNotBlank()) { "A prompt must not be blank" }
        requireNoPendingToolCalls("add a prompt")
        return evolve(
            updatedAt = at,
            messages = messages + ConversationMessage.userText(text),
            promptHistory = promptHistory + PromptEntry(at, text),
        )
    }

    fun appendAssistantMessage(message: ConversationMessage, at: Instant): Session {
        ensure(message.role == MessageRole.ASSISTANT) { "Expected an assistant message but got ${message.role}" }
        requireNoPendingToolCalls("add an assistant reply")
        return evolve(updatedAt = at, messages = messages + message)
    }

    fun recordToolResult(result: ContentBlock.ToolResult, at: Instant): Session {
        if (pendingToolCalls().none { it.id == result.toolUseId }) {
            throw TranscriptIntegrityException("Session $id has no pending tool call with id ${result.toolUseId}")
        }
        return evolve(updatedAt = at, messages = messages + ConversationMessage.toolResult(result))
    }

    /**
     * Answers every pending tool call with an error result. Used when a turn is interrupted or a
     * crashed turn is recovered, so the transcript stays valid for the next request.
     */
    fun closeDanglingToolCalls(reason: String, at: Instant): Session =
        pendingToolCalls().fold(this) { session, call ->
            session.recordToolResult(ContentBlock.ToolResult(call.id, call.name, reason, isError = true), at)
        }

    fun fork(newId: SessionId, branchName: BranchName?, at: Instant): Session {
        ensure(newId != id) { "A fork needs a new session id" }
        return Session(
            id = newId,
            workspace = workspace,
            model = model,
            createdAt = at,
            updatedAt = at,
            messages = messages,
            compaction = compaction,
            fork = SessionFork(parentSessionId = id, branchName = branchName),
            promptHistory = promptHistory,
        )
    }

    fun switchModel(newModel: ModelId, at: Instant): Session = evolve(updatedAt = at, model = newModel)

    fun summary(): SessionSummary = SessionSummary(id, workspace, model, createdAt, updatedAt, messageCount, fork)

    private fun requireNoPendingToolCalls(action: String) {
        val pending = pendingToolCalls()
        if (pending.isNotEmpty()) {
            throw TranscriptIntegrityException(
                "Cannot $action to session $id while tool calls are unanswered: ${pending.joinToString { it.id.value }}",
            )
        }
    }

    private fun evolve(
        updatedAt: Instant,
        model: ModelId? = this.model,
        messages: List<ConversationMessage> = this.messages,
        promptHistory: List<PromptEntry> = this.promptHistory,
    ): Session = Session(id, workspace, model, createdAt, updatedAt, messages, compaction, fork, promptHistory)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is Session &&
            id == other.id &&
            workspace == other.workspace &&
            model == other.model &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            messages == other.messages &&
            compaction == other.compaction &&
            fork == other.fork &&
            promptHistory == other.promptHistory

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Session(id=$id, workspace=$workspace, messages=${messages.size}, updatedAt=$updatedAt)"

    companion object {
        fun start(id: SessionId, workspace: WorkspaceRoot, model: ModelId?, at: Instant): Session =
            Session(id, workspace, model, at, at, emptyList(), null, null, emptyList())

        /**
         * Rebuilds a session from storage. Dangling tool calls are allowed here because a crash
         * can leave them behind; callers close them before the next turn.
         */
        fun restore(
            id: SessionId,
            workspace: WorkspaceRoot,
            model: ModelId?,
            createdAt: Instant,
            updatedAt: Instant,
            messages: List<ConversationMessage>,
            compaction: SessionCompaction?,
            fork: SessionFork?,
            promptHistory: List<PromptEntry>,
        ): Session = Session(id, workspace, model, createdAt, updatedAt, messages, compaction, fork, promptHistory)
    }
}
