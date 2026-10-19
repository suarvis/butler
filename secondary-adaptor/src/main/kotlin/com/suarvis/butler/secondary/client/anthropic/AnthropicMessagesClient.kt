package com.suarvis.butler.secondary.client.anthropic

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.client.support.EventStreamHttpClient
import com.suarvis.butler.secondary.client.support.HttpErrorResponse
import com.suarvis.butler.secondary.client.support.SseEvent
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

/**
 * HTTP client for the Anthropic Messages API (streaming). Uses the JDK HTTP client and parses
 * Server-Sent Events itself, as Clawcode does: the wire format is small and stable, and owning it
 * keeps transcript conversion (tool results, thinking signatures) fully under our control.
 */
class AnthropicMessagesClient(
    val provider: ProviderId,
    private val endpoint: ModelProviderProperties.Endpoint,
    httpClient: HttpClient? = null,
) {
    private val http = EventStreamHttpClient(provider, endpoint.connectTimeout, httpClient)

    fun stream(body: JsonObject, onEvent: (SseEvent) -> Unit) {
        if (endpoint.apiKey.isBlank()) {
            throw failure(Failure.NOT_CONFIGURED, "No API key configured for provider '$provider'")
        }
        if (endpoint.baseUrl.isBlank()) {
            throw failure(Failure.NOT_CONFIGURED, "No base URL configured for provider '$provider'")
        }
        val request =
            HttpRequest.newBuilder(URI.create(endpoint.baseUrl.trimEnd('/') + MESSAGES_PATH))
                .timeout(endpoint.requestTimeout)
                .header("x-api-key", endpoint.apiKey)
                .header("anthropic-version", endpoint.apiVersion)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        http.send(request, ::httpFailure, onEvent)
    }

    private fun httpFailure(error: HttpErrorResponse): ModelProviderException {
        val reason = errorMessage(error.body) ?: "HTTP ${error.status}"
        val kind =
            when {
                error.status == 401 || error.status == 403 -> Failure.AUTHENTICATION
                error.status == 429 -> Failure.RATE_LIMITED
                error.status == 529 || error.status == 503 -> Failure.OVERLOADED
                // Anthropic reports an empty prepaid balance as a 400 with this wording.
                error.status == 400 && reason.contains("credit balance", ignoreCase = true) -> Failure.QUOTA_EXHAUSTED
                error.status in 400..499 -> Failure.INVALID_REQUEST
                else -> Failure.SERVER_ERROR
            }
        return failure(kind, "Anthropic API error (${error.status}): $reason", error.retryAfterSeconds)
    }

    /** Error bodies look like {"type":"error","error":{"type":"...","message":"..."}}. */
    private fun errorMessage(body: String): String? =
        runCatching {
            val error = Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            (error?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()

    private fun failure(
        kind: Failure,
        message: String,
        retryAfterSeconds: Long? = null,
    ) = ModelProviderException(provider, kind, message, retryAfterSeconds)

    private companion object {
        const val MESSAGES_PATH = "/v1/messages"
    }
}
