package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.Session
import java.math.BigDecimal

/** What happens during a turn, in order, for whoever is displaying it. */
sealed interface TurnEvent {
    data class Started(val sessionId: SessionId, val model: ModelId, val newSession: Boolean) : TurnEvent

    data class TextDelta(val text: String) : TurnEvent

    data class ThinkingDelta(val text: String) : TurnEvent

    /** The turn is running a tool call the model made; [ToolFinished] follows with its result. */
    data class ToolStarted(val call: ContentBlock.ToolUse) : TurnEvent

    data class ToolFinished(val call: ContentBlock.ToolUse, val result: ContentBlock.ToolResult) : TurnEvent

    data class Completed(val summary: TurnSummary) : TurnEvent
}

/**
 * Receives turn events as they happen. Passed into the use case by the caller (the CLI today),
 * so core stays unaware of terminals, sockets or streams.
 */
fun interface TurnObserver {
    fun onEvent(event: TurnEvent)

    companion object {
        val NONE = TurnObserver { }
    }
}

/** Outcome of one user turn. */
data class TurnSummary(
    val session: Session,
    val model: ModelProfile,
    val assistantMessages: List<ConversationMessage>,
    val iterations: Int,
    val usage: TokenUsage,
    val stopReason: StopReason,
    /** True when the turn was cancelled; the CLI reports it differently. */
    val interrupted: Boolean = false,
) {
    val finalText: String
        get() = assistantMessages.lastOrNull()?.text.orEmpty()

    /** Null when the model has no known pricing (e.g. a local model). */
    fun estimatedCostUsd(): BigDecimal? = model.pricing?.costOf(usage)
}
