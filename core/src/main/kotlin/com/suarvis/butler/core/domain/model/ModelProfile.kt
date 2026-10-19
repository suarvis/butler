package com.suarvis.butler.core.domain.model

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ensure
import java.math.BigDecimal
import java.math.MathContext

/** USD prices per million tokens. */
data class ModelPricing(
    val inputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
    val cacheWritePerMillion: BigDecimal,
    val cacheReadPerMillion: BigDecimal,
) {
    init {
        ensure(listOf(inputPerMillion, outputPerMillion, cacheWritePerMillion, cacheReadPerMillion).all { it.signum() >= 0 }) {
            "Prices must not be negative"
        }
    }

    /** Estimated cost in USD. An estimate: providers bill from their own counters. */
    fun costOf(usage: TokenUsage): BigDecimal =
        (
            inputPerMillion * usage.inputTokens.toBigDecimal() +
                outputPerMillion * usage.outputTokens.toBigDecimal() +
                cacheWritePerMillion * usage.cacheCreationInputTokens.toBigDecimal() +
                cacheReadPerMillion * usage.cacheReadInputTokens.toBigDecimal()
        ).divide(ONE_MILLION, MathContext.DECIMAL64)

    private companion object {
        val ONE_MILLION: BigDecimal = BigDecimal(1_000_000)
    }
}

/**
 * A configured provider: [id] says who serves the request (whose key, and later whose budget and
 * rate limits); [kind] says which API dialect to speak to it.
 */
data class ModelProvider(val id: ProviderId, val kind: ProviderKind)

/**
 * What the system knows about a model: its canonical id, which provider serves it, how much it
 * may write per response, and what it costs.
 *
 * [apiModelName] is the name sent to the provider. It differs from [id] for `provider/model`
 * references: `ollama/llama3.2` is sent to Ollama as `llama3.2`. [maxOutputTokens] is null when
 * the limit is unknown; adaptors then fall back to the provider's own default. Pricing is optional
 * for unknown or local models.
 */
data class ModelProfile(
    val id: ModelId,
    val provider: ModelProvider,
    val maxOutputTokens: Int?,
    val pricing: ModelPricing?,
    val apiModelName: String = id.value,
) {
    init {
        ensure(maxOutputTokens == null || maxOutputTokens > 0) { "Max output tokens must be positive" }
        ensure(apiModelName.isNotBlank() && apiModelName.none(Char::isWhitespace)) { "Invalid provider model name '$apiModelName'" }
    }
}
