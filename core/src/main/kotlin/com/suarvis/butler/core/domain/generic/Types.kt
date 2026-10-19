package com.suarvis.butler.core.domain.generic

/** Speaker of a persisted conversation message. Tool results have their own role, as in Clawcode. */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * The API dialect a provider speaks. Several providers share a dialect: OpenAI-compatible (Chat
 * Completions) covers OpenAI, xAI, OpenRouter, Ollama and most local servers.
 */
enum class ProviderKind {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
}

/** Why the model stopped generating. */
enum class StopReason {
    END_TURN,
    MAX_TOKENS,
    TOOL_USE,
    STOP_SEQUENCE,
    REFUSAL,
    OTHER,
}
