package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.gateway.impl.AnthropicModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.ModelCatalogGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Model providers. Only Anthropic exists in this slice; the next slice adds the OpenAI-compatible
 * client and a routing gateway that picks a provider per request.
 */
@Configuration(proxyBeanMethods = false)
class ModelProviderConfig {
    @Bean
    fun modelCatalogGateway(properties: ModelCatalogProperties): ModelCatalogGateway = ModelCatalogGatewayImpl(properties)

    @Bean
    fun anthropicMessagesClient(properties: AnthropicProperties): AnthropicMessagesClient = AnthropicMessagesClient(properties)

    @Bean
    fun modelGateway(anthropic: AnthropicMessagesClient): ModelGateway = AnthropicModelGatewayImpl(anthropic)
}
