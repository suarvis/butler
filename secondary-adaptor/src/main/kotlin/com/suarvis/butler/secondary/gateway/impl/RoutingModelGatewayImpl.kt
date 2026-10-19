package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.gateway.ModelGateway

/**
 * The [ModelGateway] core sees: sends each request to the gateway of the provider that serves its
 * model. Pure dispatch; choosing a provider (budgets, rate limits) is policy and belongs in core.
 */
class RoutingModelGatewayImpl(private val providers: Map<ProviderId, ModelGateway>) : ModelGateway {
    override fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        val provider = request.model.provider.id
        val gateway =
            providers[provider]
                ?: throw ModelProviderException(provider, Failure.NOT_CONFIGURED, "Provider '$provider' is not configured under butler.providers")
        return gateway.stream(request, onEvent)
    }
}
