package com.suarvis.butler.secondary.client.openai

import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.client.openai.dto.OpenAiRequestOptions
import com.suarvis.butler.secondary.client.openai.support.OpenAiEndpointCaller
import com.suarvis.butler.secondary.client.support.SseEvent
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpClient

/**
 * HTTP client for OpenAI-compatible Chat Completions (streaming): OpenRouter, Ollama, vLLM,
 * LM Studio and others. One instance per configured provider. Same approach as the Anthropic
 * client: JDK HTTP client plus our own SSE parsing, no vendor SDK.
 */
class OpenAiChatClient(
    val provider: ProviderId,
    endpoint: ModelProviderProperties.Endpoint,
    httpClient: HttpClient? = null,
) {
    private val caller = OpenAiEndpointCaller(provider, endpoint, httpClient)

    val options = OpenAiRequestOptions(endpoint.maxTokensParameter, endpoint.streamUsage)

    fun stream(body: JsonObject, onEvent: (SseEvent) -> Unit) = caller.stream(COMPLETIONS_PATH, body, onEvent)

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
    }
}
