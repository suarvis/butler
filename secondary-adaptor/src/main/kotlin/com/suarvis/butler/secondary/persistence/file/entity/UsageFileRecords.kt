package com.suarvis.butler.secondary.persistence.file.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One line of `~/.butler/usage/<yyyy-MM>.jsonl`: a model call and what it cost. */
@Serializable
data class UsageLineRecord(
    @SerialName("at_ms") val atMs: Long,
    val provider: String,
    val model: String,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
    // A decimal string: JSON numbers would round-trip through binary floating point.
    @SerialName("cost_usd") val costUsd: String? = null,
)

/** `~/.butler/rate-limits.json`: provider id → epoch millisecond at which its cooldown ends. */
@Serializable
data class RateLimitFileRecord(
    @SerialName("cooldown_until_ms") val cooldownUntilMs: Map<String, Long> = emptyMap(),
)
