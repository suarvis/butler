package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.anthropic.AnthropicMessagesClient
import com.suarvis.butler.secondary.client.anthropic.mapper.AnthropicClientMapper
import com.suarvis.butler.secondary.client.anthropic.support.AnthropicStreamAccumulator
import com.suarvis.butler.secondary.client.anthropic.support.AnthropicStreamErrorException

/** One provider that speaks the Anthropic Messages API. */
class AnthropicModelGatewayImpl(private val client: AnthropicMessagesClient) : ModelGateway {
    override fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        val accumulator =
            AnthropicStreamAccumulator(
                onTextDelta = { onEvent(ModelStreamEvent.TextDelta(it)) },
                onThinkingDelta = { onEvent(ModelStreamEvent.ThinkingDelta(it)) },
            )
        try {
            client.stream(AnthropicClientMapper.toRequestBody(request), accumulator::accept)
            return AnthropicClientMapper.toResponse(accumulator.result())
        } catch (error: AnthropicStreamErrorException) {
            val failure = if (error.errorType == "overloaded_error") Failure.OVERLOADED else Failure.SERVER_ERROR
            throw ModelProviderException(client.provider, failure, "Anthropic stream error (${error.errorType}): ${error.message}", cause = error)
        } catch (error: ModelProviderException) {
            throw error
        } catch (error: ButlerException) {
            throw invalidResponse(error)
        } catch (error: IllegalArgumentException) {
            throw invalidResponse(error)
        }
    }

    /** Malformed JSON or content the domain rejects (e.g. an invalid tool name) is a provider problem, not user error. */
    private fun invalidResponse(error: Exception) =
        ModelProviderException(client.provider, Failure.INVALID_RESPONSE, "Unexpected response from ${client.provider}: ${error.message}", cause = error)
}
