package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.client.openai.OpenAiChatClient
import com.suarvis.butler.secondary.gateway.impl.AnthropicModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.BudgetGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.ModelCatalogGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.OpenAiCompatibleModelGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.RoutingModelGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Model providers: one client per `butler.providers.<id>` entry, chosen by its dialect, behind a
 * routing gateway, plus the catalog and budgets that routing decisions read. Clients are cheap to
 * build and connect only when a model is called.
 */
@Configuration(proxyBeanMethods = false)
class ModelProviderConfig {
    @Bean
    fun modelCatalogGateway(
        models: ModelCatalogProperties,
        providers: ModelProviderProperties,
    ): ModelCatalogGateway = ModelCatalogGatewayImpl(models, providers)

    @Bean
    fun budgetGateway(properties: BudgetProperties): BudgetGateway = BudgetGatewayImpl(properties)

    @Bean
    fun modelGateway(providers: ModelProviderProperties): ModelGateway =
        RoutingModelGatewayImpl(
            providers.providers.entries.associate { (name, endpoint) ->
                val id = ProviderId(name)
                id to
                    when (endpoint.kind) {
                        ProviderKind.ANTHROPIC -> AnthropicModelGatewayImpl(AnthropicMessagesClient(id, endpoint))
                        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleModelGatewayImpl(OpenAiChatClient(id, endpoint))
                    }
            },
        )
}
