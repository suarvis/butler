package com.suarvis.butler.secondary.client.openai.dto

/** One tool call assembled from streamed `tool_calls` deltas; arguments stay raw JSON text. */
data class OpenAiToolCall(val id: String, val name: String, val argumentsJson: String)

/** `prompt_tokens` includes the cached ones, unlike Anthropic's `input_tokens`. */
data class OpenAiUsage(val promptTokens: Long = 0, val completionTokens: Long = 0, val cachedTokens: Long = 0)

/** A streamed Chat Completions answer (first choice only), assembled. */
data class OpenAiCompletion(
    val reasoning: String,
    val text: String,
    val toolCalls: List<OpenAiToolCall>,
    val finishReason: String?,
    val usage: OpenAiUsage,
)

/** Request settings that differ between OpenAI-compatible servers. */
data class OpenAiRequestOptions(val maxTokensParameter: String, val streamUsage: Boolean)
