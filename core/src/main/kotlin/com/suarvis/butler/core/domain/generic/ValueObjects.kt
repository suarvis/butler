package com.suarvis.butler.core.domain.generic

import java.nio.file.Path

/**
 * Absolute, normalised directory a session belongs to. Sessions are scoped per workspace, as in
 * Clawcode, so the same id in two repositories never collides.
 */
@JvmInline
value class WorkspaceRoot(val path: Path) {
    init {
        ensure(path.isAbsolute) { "Workspace root must be an absolute path: $path" }
        ensure(path == path.normalize()) { "Workspace root must be normalised: $path" }
    }

    override fun toString(): String = path.toString()

    companion object {
        fun of(path: Path): WorkspaceRoot = WorkspaceRoot(path.normalize())
    }
}

/** Fork branch label, e.g. `try-async-refactor`. */
@JvmInline
value class BranchName(val value: String) {
    init {
        ensure(PATTERN.matches(value)) { "Invalid branch name '$value': use 1-100 letters, digits, '.', '_', '/' or '-'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}")
    }
}

/**
 * Raw JSON text produced by a model (tool-call arguments). Core keeps it opaque: parsing JSON is an
 * adaptor concern, and core must not depend on a JSON library.
 */
@JvmInline
value class JsonText(val value: String) {
    init {
        ensure(value.isNotBlank()) { "JSON text must not be blank" }
    }

    override fun toString(): String = value
}

/** Token counters for one model response or an aggregate of several. */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
) {
    init {
        ensure(inputTokens >= 0 && outputTokens >= 0 && cacheCreationInputTokens >= 0 && cacheReadInputTokens >= 0) {
            "Token counts must not be negative"
        }
    }

    val totalTokens: Long
        get() = inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            cacheCreationInputTokens = cacheCreationInputTokens + other.cacheCreationInputTokens,
            cacheReadInputTokens = cacheReadInputTokens + other.cacheReadInputTokens,
        )

    companion object {
        val ZERO = TokenUsage()
    }
}
