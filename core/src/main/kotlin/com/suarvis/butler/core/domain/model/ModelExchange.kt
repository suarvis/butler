package com.suarvis.butler.core.domain.model

import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ensure
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.tool.ToolDefinition

/** One provider-neutral model call: which model, the system prompt, the transcript so far and the tools on offer. */
data class ModelRequest(
    val model: ModelProfile,
    val systemPrompt: String,
    val messages: List<ConversationMessage>,
    /** Tools offered for this call; empty until lane A's tool loop, and provider mappers ignore it until lane B. */
    val tools: List<ToolDefinition> = emptyList(),
) {
    init {
        ensure(messages.isNotEmpty()) { "A model request needs at least one message" }
    }
}

/** The assembled result of one model call. */
data class ModelResponse(
    val blocks: List<ContentBlock>,
    val usage: TokenUsage,
    val stopReason: StopReason,
) {
    init {
        ensure(blocks.none { it is ContentBlock.ToolResult }) { "A model response cannot contain tool results" }
    }

    /** A provider may answer with no content at all; the transcript still needs a non-empty message. */
    fun toAssistantMessage(): ConversationMessage =
        ConversationMessage.assistant(blocks.ifEmpty { listOf(ContentBlock.Text("")) }, usage)
}

/** Incremental output while a response streams, for live display. */
sealed interface ModelStreamEvent {
    data class TextDelta(val text: String) : ModelStreamEvent

    data class ThinkingDelta(val text: String) : ModelStreamEvent
}
