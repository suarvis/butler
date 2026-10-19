package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.secondary.config.ModelCatalogProperties
import com.suarvis.butler.secondary.config.ModelProviderProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class ModelCatalogGatewayImplTest {
    private val providers =
        ModelProviderProperties(
            mapOf(
                "anthropic" to ModelProviderProperties.Endpoint(kind = ProviderKind.ANTHROPIC, modelPrefixes = listOf("claude")),
                "openai" to ModelProviderProperties.Endpoint(modelPrefixes = listOf("gpt-", "o3")),
                "openrouter" to ModelProviderProperties.Endpoint(modelPrefixes = listOf("gpt-oss")),
                "ollama" to ModelProviderProperties.Endpoint(apiKeyRequired = false),
            ),
        )

    private val catalog =
        ModelCatalogGatewayImpl(
            ModelCatalogProperties(
                default = "sonnet",
                aliases = mapOf("sonnet" to "claude-sonnet-4-6", "local" to "ollama/qwen3-coder"),
                profiles =
                    mapOf(
                        "claude-sonnet-4-6" to
                            ModelCatalogProperties.Profile(
                                provider = "anthropic",
                                maxOutputTokens = 64_000,
                                pricing = ModelCatalogProperties.Pricing(BigDecimal("3"), BigDecimal("15"), BigDecimal("3.75"), BigDecimal("0.30")),
                            ),
                        "kimi" to ModelCatalogProperties.Profile(provider = "openrouter", apiModel = "moonshotai/kimi-k2"),
                        "orphan" to ModelCatalogProperties.Profile(provider = "nowhere"),
                    ),
            ),
            providers,
        )

    @Test
    fun `aliases resolve to the configured profile and provider`() {
        val profile = catalog.resolve(ModelId("Sonnet"))

        assertEquals(ModelId("claude-sonnet-4-6"), profile.id)
        assertEquals(ProviderId("anthropic"), profile.provider.id)
        assertEquals(ProviderKind.ANTHROPIC, profile.provider.kind)
        assertEquals(64_000, profile.maxOutputTokens)
        assertEquals(BigDecimal("15"), profile.pricing?.outputPerMillion)
        assertEquals(ModelId("sonnet"), catalog.defaultModel())
    }

    @Test
    fun `provider slash model picks the provider and sends the rest as the model name`() {
        val local = catalog.resolve(ModelId("local"))
        val routed = catalog.resolve(ModelId("openrouter/qwen/qwen3-coder"))

        assertEquals(ModelId("ollama/qwen3-coder"), local.id)
        assertEquals(ProviderId("ollama"), local.provider.id)
        assertEquals("qwen3-coder", local.apiModelName)
        assertEquals(ProviderId("openrouter"), routed.provider.id)
        assertEquals("qwen/qwen3-coder", routed.apiModelName)
    }

    @Test
    fun `models missing from the catalog go to the provider with the longest matching prefix`() {
        val gpt = catalog.resolve(ModelId("gpt-5.5"))
        val oss = catalog.resolve(ModelId("gpt-oss-120b"))
        val claude = catalog.resolve(ModelId("claude-future-9"))

        assertEquals(ProviderId("openai"), gpt.provider.id)
        assertNull(gpt.maxOutputTokens)
        assertNull(gpt.pricing)
        assertEquals(ProviderId("openrouter"), oss.provider.id)
        assertEquals(ProviderKind.ANTHROPIC, claude.provider.kind)
    }

    @Test
    fun `a profile can name the provider's own model name`() {
        val kimi = catalog.resolve(ModelId("kimi"))

        assertEquals(ProviderId("openrouter"), kimi.provider.id)
        assertEquals("moonshotai/kimi-k2", kimi.apiModelName)
    }

    @Test
    fun `models no configured provider serves are rejected with guidance`() {
        val unknown = assertThrows<UnknownModelException> { catalog.resolve(ModelId("mistral-large")) }
        val unconfigured = assertThrows<UnknownModelException> { catalog.resolve(ModelId("orphan")) }

        assertTrue(unknown.message!!.contains("ollama/mistral-large"), unknown.message)
        assertTrue(unconfigured.message!!.contains("'nowhere'"), unconfigured.message)
    }
}
