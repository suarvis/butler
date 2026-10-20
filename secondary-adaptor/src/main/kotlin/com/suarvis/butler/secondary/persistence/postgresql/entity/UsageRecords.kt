package com.suarvis.butler.secondary.persistence.postgresql.entity

import java.math.BigDecimal
import java.time.Instant

/** A row of `butler_usage`. */
data class UsageRow(
    val recordedAt: Instant,
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
    val costUsd: BigDecimal?,
)

/** `butler_usage` grouped by provider and model. */
data class UsageTotalRow(
    val provider: String,
    val model: String,
    val calls: Long,
    val tokens: Long,
    val costUsd: BigDecimal,
)

/** A row of `butler_rate_limit`. */
data class RateLimitRow(val provider: String, val cooldownUntil: Instant)
