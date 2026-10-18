package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.secondary.config.ModelCatalogProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ModelCatalogGatewayImplTest {
    private val catalog =
        ModelCatalogGatewayImpl(
            ModelCatalogProperties(
                default = "sonnet",
                aliases = mapOf("sonnet" to "claude-sonnet-4-6"),
                profiles =
                    mapOf(
                        "claude-sonnet-4-6" to
                            ModelCatalogProperties.Profile(
                                provider = ProviderKind.ANTHROPIC,
                                maxOutputTokens = 64_000,
                                pricing = ModelCatalogProperties.Pricing(BigDecimal("3"), BigDecimal("15"), BigDecimal("3.75"), BigDecimal("0.30")),
                            ),
                    ),
            ),
        )

    @Test
    fun `aliases resolve to the configured profile`() {
        val profile = catalog.resolve(ModelId("Sonnet"))

        assertEquals(ModelId("claude-sonnet-4-6"), profile.id)
        assertEquals(64_000, profile.maxOutputTokens)
        assertEquals(BigDecimal("15"), profile.pricing?.outputPerMillion)
        assertEquals(ModelId("sonnet"), catalog.defaultModel())
    }

    @Test
    fun `unknown models pass through with a guessed provider and no pricing`() {
        val claude = catalog.resolve(ModelId("claude-future-9"))
        val local = catalog.resolve(ModelId("qwen2.5-coder"))

        assertEquals(ProviderKind.ANTHROPIC, claude.provider)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, local.provider)
        assertEquals(32_000, local.maxOutputTokens)
        assertNull(local.pricing)
    }
}
