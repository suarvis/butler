package com.suarvis.butler.core.domain.model

import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import java.security.MessageDigest

/**
 * Prepares a stored transcript for the next model request, whichever provider serves it. The
 * stored session is never changed; this only decides what of it can be sent, which is what lets
 * a session move between providers (Anthropic → OpenAI → Ollama) part-way through.
 *
 * - Thinking blocks are dropped: their signatures are only valid for the provider that produced
 *   them, and thinking is not requested yet.
 * - System messages are dropped: the system prompt is composed for every turn.
 * - A tool call is sent as a native tool call only when its tool is offered in this request and
 *   its result follows it. Otherwise the call and its result are turned into plain text, so the
 *   model keeps the context. Providers reject tool blocks for tools the request does not define,
 *   and tool results that answer no call.
 * - Tool-call ids that another provider could reject (characters, length) or that repeat are
 *   replaced, consistently for the call and its result.
 * - Empty text is dropped, and adjacent messages of the same speaker are merged.
 */
object TranscriptNormalizer {
    fun normalize(messages: List<ConversationMessage>, offeredTools: Set<ToolName>): List<ConversationMessage> {
        val normalized = mutableListOf<ConversationMessage>()
        val usedIds = mutableSetOf<String>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                MessageRole.SYSTEM -> index++
                MessageRole.USER -> {
                    textOnly(message)?.let(normalized::add)
                    index++
                }
                // Results with no assistant message before them answer nothing that will be sent.
                MessageRole.TOOL -> {
                    normalized += ConversationMessage(MessageRole.USER, message.toolResults.map(::describeResult))
                    index++
                }
                MessageRole.ASSISTANT -> {
                    var end = index + 1
                    while (end < messages.size && messages[end].role == MessageRole.TOOL) end++
                    normalized += normalizeExchange(message, messages.subList(index + 1, end), offeredTools, usedIds)
                    index = end
                }
            }
        }
        return mergeAdjacent(normalized)
    }

    /** One assistant message and the tool messages that directly follow it. */
    private fun normalizeExchange(
        assistant: ConversationMessage,
        toolMessages: List<ConversationMessage>,
        offeredTools: Set<ToolName>,
        usedIds: MutableSet<String>,
    ): List<ConversationMessage> {
        val results = toolMessages.flatMap { it.toolResults }
        val answeredCalls = results.map { it.toolUseId }.toSet()
        val nativeIds = mutableMapOf<ToolCallId, ToolCallId>()

        val blocks =
            assistant.blocks.mapNotNull { block ->
                when (block) {
                    is ContentBlock.Text -> block.takeIf { it.text.isNotEmpty() }
                    is ContentBlock.Thinking, is ContentBlock.ToolResult -> null
                    is ContentBlock.ToolUse ->
                        if (block.name in offeredTools && block.id in answeredCalls && block.id !in nativeIds) {
                            val portable = portableId(block.id, usedIds)
                            nativeIds[block.id] = portable
                            block.copy(id = portable)
                        } else {
                            describeCall(block)
                        }
                }
            }

        val nativeResults = mutableListOf<ContentBlock.ToolResult>()
        val textResults = mutableListOf<ContentBlock>()
        val answered = mutableSetOf<ToolCallId>()
        for (result in results) {
            val portable = nativeIds[result.toolUseId]
            if (portable != null && answered.add(result.toolUseId)) {
                nativeResults += result.copy(toolUseId = portable)
            } else {
                textResults += describeResult(result)
            }
        }

        return buildList {
            if (blocks.isNotEmpty()) add(ConversationMessage.assistant(blocks, assistant.usage))
            if (nativeResults.isNotEmpty()) add(ConversationMessage(MessageRole.TOOL, nativeResults))
            if (textResults.isNotEmpty()) add(ConversationMessage(MessageRole.USER, textResults))
        }
    }

    private fun textOnly(message: ConversationMessage): ConversationMessage? {
        val texts = message.blocks.filter { it is ContentBlock.Text && it.text.isNotEmpty() }
        return if (texts.isEmpty()) null else message.copy(blocks = texts)
    }

    /**
     * Merges neighbours with the same speaker, which text-converted tool results produce. An
     * assistant message with native tool calls is never merged: its results must follow it.
     */
    private fun mergeAdjacent(messages: List<ConversationMessage>): List<ConversationMessage> =
        messages.fold(mutableListOf<ConversationMessage>()) { merged, message ->
            val previous = merged.lastOrNull()
            val mergeable =
                previous != null &&
                    previous.role == message.role &&
                    (message.role == MessageRole.USER || (message.role == MessageRole.ASSISTANT && previous.toolUses.isEmpty()))
            if (mergeable) {
                merged[merged.lastIndex] = previous.copy(blocks = previous.blocks + message.blocks, usage = message.usage ?: previous.usage)
            } else {
                merged += message
            }
            merged
        }

    /** Ids every supported provider accepts: Anthropic's character set within OpenAI's length limit. */
    private fun portableId(id: ToolCallId, usedIds: MutableSet<String>): ToolCallId {
        var candidate = id.value
        var attempt = 0
        while (!PORTABLE_ID.matches(candidate) || candidate in usedIds) {
            candidate = "call_" + sha256("${id.value}#${attempt++}").take(DERIVED_ID_HASH_LENGTH)
        }
        usedIds += candidate
        return ToolCallId(candidate)
    }

    private fun describeCall(call: ContentBlock.ToolUse): ContentBlock.Text = ContentBlock.Text("[tool call ${call.name} (${call.id})] ${call.input}")

    private fun describeResult(result: ContentBlock.ToolResult): ContentBlock.Text {
        val label = if (result.isError) "tool error" else "tool result"
        return ContentBlock.Text("[$label ${result.toolName} (${result.toolUseId})] ${result.output}")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val PORTABLE_ID = Regex("[A-Za-z0-9_-]{1,40}")
    private const val DERIVED_ID_HASH_LENGTH = 24
}
