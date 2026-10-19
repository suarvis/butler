package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.secondary.client.openai.OpenAiChatClient
import com.suarvis.butler.secondary.client.openai.mapper.OpenAiClientMapper
import com.suarvis.butler.secondary.client.openai.support.OpenAiStreamAccumulator
import com.suarvis.butler.secondary.client.openai.support.OpenAiStreamErrorException

/** One provider that speaks OpenAI-compatible Chat Completions. */
class OpenAiCompatibleModelGatewayImpl(private val client: OpenAiChatClient) : ModelGateway {
    override fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        val accumulator =
            OpenAiStreamAccumulator(
                onTextDelta = { onEvent(ModelStreamEvent.TextDelta(it)) },
                onThinkingDelta = { onEvent(ModelStreamEvent.ThinkingDelta(it)) },
            )
        try {
            client.stream(OpenAiClientMapper.toRequestBody(request, client.options), accumulator::accept)
            return OpenAiClientMapper.toResponse(accumulator.result())
        } catch (error: OpenAiStreamErrorException) {
            val failure =
                when (error.code) {
                    "insufficient_quota" -> Failure.QUOTA_EXHAUSTED
                    "rate_limit_exceeded" -> Failure.RATE_LIMITED
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
