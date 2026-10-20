package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.domain.generic.ProviderKind
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * `butler.models.*`: the model catalog. Kept in configuration rather than code (Clawcode
 * hard-codes it and matches prices by substring) so new models and price changes need no release.
 */
@ConfigurationProperties(prefix = "butler.models")
data class ModelCatalogProperties(
    val default: String = "sonnet",
    val aliases: Map<String, String> = emptyMap(),
    val profiles: Map<String, Profile> = emptyMap(),
    /** Offered in this order when the chosen model is over budget, rate limited or out of quota. */
    val fallbacks: List<String> = emptyList(),
) {
    /**
     * @param provider id of the entry under `butler.providers` that serves this model.
     * @param apiModel name sent to the provider when it differs from the catalog id.
     * @param contextWindow input tokens the model accepts; fallbacks too small for the conversation are not offered.
     */
    data class Profile(
        val provider: String? = null,
        val apiModel: String? = null,
        val maxOutputTokens: Int? = null,
        val contextWindow: Int? = null,
        val pricing: Pricing? = null,
    )

    /** USD per million tokens. */
    data class Pricing(
        val input: BigDecimal,
        val output: BigDecimal,
        val cacheWrite: BigDecimal = BigDecimal.ZERO,
        val cacheRead: BigDecimal = BigDecimal.ZERO,
    )
}

/**
 * `butler.providers.<id>.*`: the providers Butler can call. Bring your own key: each key is the
 * user's own, read from the environment in application.yml and never stored by Butler. Adding a
 * provider is configuration only, as long as it speaks a known dialect ([ProviderKind]).
 */
@ConfigurationProperties(prefix = "butler")
data class ModelProviderProperties(
    val providers: Map<String, Endpoint> = emptyMap(),
) {
    /**
     * @param modelPrefixes model ids this provider serves when they are not in the catalog,
     *   e.g. `gpt-` for OpenAI; the longest matching prefix across providers wins.
     * @param apiKeyRequired false for local servers such as Ollama that accept any or no key.
     * @param apiVersion Anthropic only: the `anthropic-version` header.
     * @param maxTokensParameter Chat Completions only: OpenAI wants `max_completion_tokens`,
     *   while many compatible servers only understand the older `max_tokens`.
     * @param streamUsage Chat Completions only: ask for token usage at the end of the stream;
     *   turn off for servers that reject `stream_options`.
     */
    data class Endpoint(
        val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        val baseUrl: String = "",
        val apiKey: String = "",
        val apiKeyRequired: Boolean = true,
        val modelPrefixes: List<String> = emptyList(),
        val apiVersion: String = "2023-06-01",
        val maxTokensParameter: String = "max_tokens",
        val streamUsage: Boolean = true,
        val connectTimeout: Duration = Duration.ofSeconds(30),
        val requestTimeout: Duration = Duration.ofMinutes(10),
    )
}

/**
 * `butler.budgets.*`: monthly limits per provider and per model, in estimated USD and/or tokens.
 * Months follow the system time zone. With no entries nothing is limited.
 */
@ConfigurationProperties(prefix = "butler.budgets")
data class BudgetProperties(
    val providers: Map<String, Limit> = emptyMap(),
    val models: Map<String, Limit> = emptyMap(),
) {
    data class Limit(
        val monthlyUsd: BigDecimal? = null,
        val monthlyTokens: Long? = null,
    )
}
