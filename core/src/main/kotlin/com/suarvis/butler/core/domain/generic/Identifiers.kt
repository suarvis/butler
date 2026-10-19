package com.suarvis.butler.core.domain.generic

/**
 * Session identifier. The character set is restricted because file-based storage uses the id
 * as a file name, so it must never contain path separators.
 */
@JvmInline
value class SessionId(val value: String) {
    init {
        ensure(PATTERN.matches(value)) {
            "Invalid session id '$value': use 1-128 letters, digits, '.', '_' or '-', starting with a letter or digit"
        }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/** Identifier a model provider assigns to one tool call (e.g. `toolu_01...`, `call_...`). */
@JvmInline
value class ToolCallId(val value: String) {
    init {
        ensure(value.isNotBlank() && value.length <= MAX_LENGTH) { "Tool call id must be 1-$MAX_LENGTH non-blank characters" }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 256
    }
}

/** Name of a tool the model can call: built-in (`read_file`) or MCP-provided (`mcp__server__tool`). */
@JvmInline
value class ToolName(val value: String) {
    init {
        ensure(PATTERN.matches(value)) { "Invalid tool name '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}

/** Provider model identifier or alias as the user wrote it, e.g. `sonnet` or `gpt-4.1`. */
@JvmInline
value class ModelId(val value: String) {
    init {
        ensure(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(Char::isWhitespace)) {
            "Invalid model id '$value'"
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 200
    }
}

/**
 * A configured model provider, e.g. `anthropic`, `openai`, `xai`, `ollama`. It names who serves a
 * request: whose API key is used and, later, whose budget and rate limits apply. Lower case
 * because it is also the prefix in `provider/model` references such as `ollama/llama3.2`.
 */
@JvmInline
value class ProviderId(val value: String) {
    init {
        ensure(PATTERN.matches(value)) { "Invalid provider id '$value': use 1-64 lower-case letters, digits, '_' or '-'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    }
}
