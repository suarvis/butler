package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.model.ModelPricing
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.secondary.config.ModelCatalogProperties

/** Catalog backed by `butler.models.*` configuration. */
class ModelCatalogGatewayImpl(private val properties: ModelCatalogProperties) : ModelCatalogGateway {
    override fun defaultModel(): ModelId = ModelId(properties.default)

    override fun resolve(model: ModelId): ModelProfile {
        val canonical = properties.aliases[model.value.lowercase()] ?: model.value
        val profile = properties.profiles[canonical]
        return ModelProfile(
            id = ModelId(canonical),
            provider = profile?.provider ?: guessProvider(canonical),
            maxOutputTokens = profile?.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
            pricing =
                profile?.pricing?.let {
                    ModelPricing(it.input, it.output, it.cacheWrite, it.cacheRead)
                },
        )
    }

    /** Same prefix rule as Clawcode for models that are not in the catalog. */
    private fun guessProvider(model: String): ProviderKind =
        if (model.startsWith("claude")) ProviderKind.ANTHROPIC else ProviderKind.OPENAI_COMPATIBLE

    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 32_000
    }
}
