package com.suarvis.butler.api.cli.render

import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.Session

/**
 * Markdown transcript for `butler export`. A presentation format of the primary adaptor, so it
 * reads the domain session directly but produces only text.
 */
object SessionMarkdownRenderer {
    fun render(session: Session): String =
        buildString {
            appendLine("# Conversation export")
            appendLine()
            appendLine("- Session: `${session.id}`")
            appendLine("- Workspace: `${session.workspace}`")
            appendLine("- Model: ${session.model ?: "default"}")
            appendLine("- Messages: ${session.messageCount}")
            session.fork?.let { appendLine("- Forked from: `${it.parentSessionId}`" + it.branchName?.let { b -> " (branch $b)" }.orEmpty()) }
            session.messages.forEachIndexed { index, message ->
                appendLine()
                appendLine("## ${index + 1}. ${title(message.role)}")
                message.blocks.forEach { block ->
                    appendLine()
                    append(render(block))
                }
            }
        }

    private fun title(role: MessageRole): String =
        when (role) {
            MessageRole.SYSTEM -> "System"
            MessageRole.USER -> "User"
            MessageRole.ASSISTANT -> "Assistant"
            MessageRole.TOOL -> "Tool result"
        }

    private fun render(block: ContentBlock): String =
        when (block) {
            is ContentBlock.Text -> block.text.trimEnd() + "\n"
            is ContentBlock.Thinking -> block.thinking.lines().joinToString(separator = "\n", postfix = "\n") { "> $it" }
            is ContentBlock.ToolUse -> "**Tool call** `${block.name}` (`${block.id}`)\n\n${fenced(block.input.value, "json")}"
            is ContentBlock.ToolResult -> {
                val status = if (block.isError) " — error" else ""
                "**${block.toolName}**$status\n\n${fenced(block.output, "")}"
            }
        }

    private fun fenced(content: String, language: String): String {
        val fence = if (content.contains("```")) "````" else "```"
        return "$fence$language\n${content.trimEnd()}\n$fence\n"
    }
}
