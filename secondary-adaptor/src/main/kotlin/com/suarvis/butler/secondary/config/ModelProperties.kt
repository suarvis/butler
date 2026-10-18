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
) {
    data class Profile(
        val provider: ProviderKind? = null,
        val maxOutputTokens: Int? = null,
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
 * `butler.providers.anthropic.*`. The API key is the user's own (BYOK); it defaults to the
 * ANTHROPIC_API_KEY environment variable in application.yml and is never stored by Butler.
 */
@ConfigurationProperties(prefix = "butler.providers.anthropic")
data class AnthropicProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.anthropic.com",
    val apiVersion: String = "2023-06-01",
    val connectTimeout: Duration = Duration.ofSeconds(30),
    val requestTimeout: Duration = Duration.ofMinutes(10),
)
