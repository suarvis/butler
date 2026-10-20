package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.model.ModelPricing
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelProvider
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.secondary.config.ModelCatalogProperties
import com.suarvis.butler.secondary.config.ModelProviderProperties

/**
 * Catalog backed by `butler.models.*` and `butler.providers.*` configuration.
 *
 * Which provider serves a model is decided, in order, by: its catalog profile; an explicit
 * `provider/model` reference (`ollama/llama3.2`, `openrouter/qwen/qwen3-coder`); the longest
 * `model-prefixes` entry of a provider (`gpt-` → openai, `grok` → xai). Clawcode guesses by
 * prefix in code; here the rules are configuration, so a new provider needs no release.
 */
class ModelCatalogGatewayImpl(
    private val models: ModelCatalogProperties,
    private val providers: ModelProviderProperties,
) : ModelCatalogGateway {
    override fun defaultModel(): ModelId = ModelId(models.default)

    override fun fallbacks(): List<ModelId> = models.fallbacks.map(::ModelId)

    override fun isConfigured(provider: ProviderId): Boolean {
        val endpoint = providers.providers[provider.value] ?: return false
        return endpoint.baseUrl.isNotBlank() && (endpoint.apiKey.isNotBlank() || !endpoint.apiKeyRequired)
    }

    override fun resolve(model: ModelId): ModelProfile {
        val canonical = models.aliases[model.value.lowercase()] ?: model.value
        val profile = models.profiles[canonical]
        val reference = providerReference(canonical)
        val providerName =
            profile?.provider
                ?: reference?.first
                ?: providerByPrefix(canonical)
                ?: throw UnknownModelException(
                    ModelId(canonical),
                    "no configured provider serves it. Use provider/model (for example ollama/$canonical) " +
                        "or add it under butler.models.profiles",
                )
        val endpoint =
            providers.providers[providerName]
                ?: throw UnknownModelException(ModelId(canonical), "its provider '$providerName' is not configured under butler.providers")
        return ModelProfile(
            id = ModelId(canonical),
            provider = ModelProvider(ProviderId(providerName), endpoint.kind),
            maxOutputTokens = profile?.maxOutputTokens,
            pricing = profile?.pricing?.let { ModelPricing(it.input, it.output, it.cacheWrite, it.cacheRead) },
            apiModelName = profile?.apiModel ?: reference?.takeIf { it.first == providerName }?.second ?: canonical,
            contextWindow = profile?.contextWindow,
        )
    }

    /** `ollama/llama3.2` → (ollama, llama3.2), but only when `ollama` is a configured provider. */
    private fun providerReference(model: String): Pair<String, String>? {
        val prefix = model.substringBefore('/', missingDelimiterValue = "")
        val rest = model.substringAfter('/')
        return if (prefix in providers.providers && rest.isNotEmpty()) prefix to rest else null
    }

    private fun providerByPrefix(model: String): String? =
        providers.providers
            .flatMap { (name, endpoint) -> endpoint.modelPrefixes.map { prefix -> prefix.lowercase() to name } }
            .filter { (prefix, _) -> prefix.isNotEmpty() && model.lowercase().startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.second
}
