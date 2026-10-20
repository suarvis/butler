package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.openai.OpenAiResponsesClient
import com.suarvis.butler.secondary.client.openai.mapper.OpenAiResponsesClientMapper
import com.suarvis.butler.secondary.client.openai.support.OpenAiResponsesStreamAccumulator
import com.suarvis.butler.secondary.client.openai.support.OpenAiResponsesStreamErrorException

/** One provider that speaks the OpenAI Responses API (OpenAI, xAI). */
class OpenAiResponsesModelGatewayImpl(private val client: OpenAiResponsesClient) : ModelGateway {
    override fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        val accumulator =
            OpenAiResponsesStreamAccumulator(
                onTextDelta = { onEvent(ModelStreamEvent.TextDelta(it)) },
                onThinkingDelta = { onEvent(ModelStreamEvent.ThinkingDelta(it)) },
            )
        try {
            client.stream(OpenAiResponsesClientMapper.toRequestBody(request), accumulator::accept)
            return OpenAiResponsesClientMapper.toResponse(accumulator.result())
        } catch (error: OpenAiResponsesStreamErrorException) {
            val failure =
                when (error.code) {
                    "insufficient_quota" -> Failure.QUOTA_EXHAUSTED
                    "rate_limit_exceeded" -> Failure.RATE_LIMITED
                    "server_is_overloaded" -> Failure.OVERLOADED
                    else -> Failure.SERVER_ERROR
                }
            throw ModelProviderException(client.provider, failure, "${client.provider} stream error: ${error.message}", cause = error)
        } catch (error: ModelProviderException) {
            throw error
        } catch (error: ButlerException) {
            throw invalidResponse(error)
        } catch (error: IllegalArgumentException) {
            throw invalidResponse(error)
        }
    }

    /** Malformed JSON or content the domain rejects (e.g. a tool call without a name) is a provider problem. */
    private fun invalidResponse(error: Exception) =
        ModelProviderException(client.provider, Failure.INVALID_RESPONSE, "Unexpected response from ${client.provider}: ${error.message}", cause = error)
}
