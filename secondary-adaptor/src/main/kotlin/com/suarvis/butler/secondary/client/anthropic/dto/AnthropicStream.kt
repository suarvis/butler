package com.suarvis.butler.secondary.client.anthropic.dto

/*
 * Client-side model of an Anthropic Messages streaming response, assembled from SSE events.
 * Kept separate from the domain: AnthropicClientMapper translates.
 */

sealed interface AnthropicBlock {
    data class Text(val text: String) : AnthropicBlock

    data class Thinking(val thinking: String, val signature: String?) : AnthropicBlock

    /** [inputJson] is the tool input as streamed (`input_json_delta` fragments joined). */
    data class ToolUse(val id: String, val name: String, val inputJson: String) : AnthropicBlock
}

data class AnthropicUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
)

data class AnthropicMessage(
    val blocks: List<AnthropicBlock>,
    val usage: AnthropicUsage,
    val stopReason: String?,
)
