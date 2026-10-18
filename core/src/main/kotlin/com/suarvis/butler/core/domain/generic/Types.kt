package com.suarvis.butler.core.domain.generic

/** Speaker of a persisted conversation message. Tool results have their own role, as in Clawcode. */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}
