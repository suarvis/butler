package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ensure

/**
 * One message in a transcript. The role decides which blocks are legal, so an invalid shape is
 * rejected when the message is created rather than when a provider refuses it.
 */
data class ConversationMessage(
    val role: MessageRole,
    val blocks: List<ContentBlock>,
    val usage: TokenUsage? = null,
) {
    init {
        ensure(blocks.isNotEmpty()) { "A message needs at least one content block" }
        when (role) {
            MessageRole.TOOL ->
                ensure(blocks.all { it is ContentBlock.ToolResult }) { "Tool messages may only contain tool results" }
            MessageRole.ASSISTANT ->
                ensure(blocks.none { it is ContentBlock.ToolResult }) { "Assistant messages cannot contain tool results" }
            MessageRole.USER, MessageRole.SYSTEM ->
                ensure(blocks.none { it is ContentBlock.ToolUse || it is ContentBlock.ToolResult }) {
                    "${role.name.lowercase()} messages cannot contain tool calls or tool results"
                }
        }
    }

    val toolUses: List<ContentBlock.ToolUse>
        get() = blocks.filterIsInstance<ContentBlock.ToolUse>()

    val toolResults: List<ContentBlock.ToolResult>
        get() = blocks.filterIsInstance<ContentBlock.ToolResult>()

    val text: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString(separator = "\n") { it.text }

    companion object {
        fun userText(text: String): ConversationMessage =
            ConversationMessage(MessageRole.USER, listOf(ContentBlock.Text(text)))

        fun assistant(blocks: List<ContentBlock>, usage: TokenUsage? = null): ConversationMessage =
            ConversationMessage(MessageRole.ASSISTANT, blocks, usage)

        fun toolResult(result: ContentBlock.ToolResult): ConversationMessage =
            ConversationMessage(MessageRole.TOOL, listOf(result))
    }
}
