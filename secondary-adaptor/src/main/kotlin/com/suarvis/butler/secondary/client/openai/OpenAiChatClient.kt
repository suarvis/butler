package com.suarvis.butler.secondary.client.openai

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.secondary.client.openai.dto.OpenAiRequestOptions
import com.suarvis.butler.secondary.client.support.EventStreamHttpClient
import com.suarvis.butler.secondary.client.support.HttpErrorResponse
import com.suarvis.butler.secondary.client.support.SseEvent
import com.suarvis.butler.secondary.config.ModelProviderProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

/**
 * HTTP client for OpenAI-compatible Chat Completions (streaming): OpenAI, xAI, OpenRouter,
 * Ollama, vLLM, LM Studio and others. One instance per configured provider. Same approach as the
 * Anthropic client: JDK HTTP client plus our own SSE parsing, no vendor SDK.
 */
class OpenAiChatClient(
    val provider: ProviderId,
    private val endpoint: ModelProviderProperties.Endpoint,
    httpClient: HttpClient? = null,
) {
    private val http = EventStreamHttpClient(provider, endpoint.connectTimeout, httpClient)

    val options = OpenAiRequestOptions(endpoint.maxTokensParameter, endpoint.streamUsage)

    fun stream(body: JsonObject, onEvent: (SseEvent) -> Unit) {
        if (endpoint.apiKeyRequired && endpoint.apiKey.isBlank()) {
            throw failure(Failure.NOT_CONFIGURED, "No API key configured for provider '$provider'")
        }
        if (endpoint.baseUrl.isBlank()) {
            throw failure(Failure.NOT_CONFIGURED, "No base URL configured for provider '$provider'")
        }
        val request =
            HttpRequest.newBuilder(URI.create(endpoint.baseUrl.trimEnd('/') + COMPLETIONS_PATH))
                .timeout(endpoint.requestTimeout)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .apply { if (endpoint.apiKey.isNotBlank()) header("authorization", "Bearer ${endpoint.apiKey}") }
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        http.send(request, ::httpFailure, onEvent)
    }

    private fun httpFailure(error: HttpErrorResponse): ModelProviderException {
        val details = errorDetails(error.body)
        val reason = details?.message ?: error.body.take(MAX_RAW_ERROR_LENGTH).ifBlank { "HTTP ${error.status}" }
        val kind =
            when {
                error.status == 401 || error.status == 403 -> Failure.AUTHENTICATION
                // OpenAI signals an exhausted quota with 429 too, but waiting will not help.
                error.status == 429 && details?.code == "insufficient_quota" -> Failure.QUOTA_EXHAUSTED
                error.status == 402 -> Failure.QUOTA_EXHAUSTED
                error.status == 429 -> Failure.RATE_LIMITED
                error.status == 503 || error.status == 529 -> Failure.OVERLOADED
                error.status in 400..499 -> Failure.INVALID_REQUEST
                else -> Failure.SERVER_ERROR
            }
        return failure(kind, "$provider API error (${error.status}): $reason", error.retryAfterSeconds)
    }

    private data class ErrorDetails(val message: String?, val code: String?)

    /** OpenAI style is {"error":{"message","type","code"}}; some servers send {"error":"text"}. */
    private fun errorDetails(body: String): ErrorDetails? =
        runCatching {
            when (val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error")) {
                is JsonObject ->
                    ErrorDetails(
                        message = (error["message"] as? JsonPrimitive)?.contentOrNull,
                        code = ((error["code"] as? JsonPrimitive)?.contentOrNull) ?: (error["type"] as? JsonPrimitive)?.contentOrNull,
                    )
                is JsonPrimitive -> ErrorDetails(error.contentOrNull, null)
                else -> null
            }
        }.getOrNull()

    private fun failure(
        kind: Failure,
        message: String,
        retryAfterSeconds: Long? = null,
    ) = ModelProviderException(provider, kind, message, retryAfterSeconds)

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val MAX_RAW_ERROR_LENGTH = 300
    }
}
