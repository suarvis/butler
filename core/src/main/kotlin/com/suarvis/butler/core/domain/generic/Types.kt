package com.suarvis.butler.core.domain.generic

/** Speaker of a persisted conversation message. Tool results have their own role, as in Clawcode. */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/** Wire protocol family of a model provider. OpenAI-compatible covers OpenAI, xAI, OpenRouter, Ollama, ... */
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
