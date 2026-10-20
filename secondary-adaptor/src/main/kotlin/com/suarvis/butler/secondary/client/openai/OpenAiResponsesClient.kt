package com.suarvis.butler.secondary.client.openai

import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.client.openai.support.OpenAiEndpointCaller
import com.suarvis.butler.secondary.client.support.SseEvent
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpClient

/**
 * HTTP client for the OpenAI Responses API (streaming), as served by OpenAI and xAI. Used
 * statelessly: every request carries the whole normalized transcript and asks the provider not to
 * store it, because Butler keeps sessions itself.
 */
class OpenAiResponsesClient(
    val provider: ProviderId,
    endpoint: ModelProviderProperties.Endpoint,
    httpClient: HttpClient? = null,
) {
    private val caller = OpenAiEndpointCaller(provider, endpoint, httpClient)

    fun stream(body: JsonObject, onEvent: (SseEvent) -> Unit) = caller.stream(RESPONSES_PATH, body, onEvent)

    private companion object {
        const val RESPONSES_PATH = "/responses"
    }
}
