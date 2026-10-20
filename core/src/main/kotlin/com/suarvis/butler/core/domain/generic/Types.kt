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
 * Completions) covers OpenRouter, Ollama and most local servers; the OpenAI Responses API is what
 * OpenAI and xAI recommend, and the only way OpenAI's newest models call tools.
 */
enum class ProviderKind {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    OPENAI_RESPONSES,
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
