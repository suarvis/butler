package com.suarvis.butler.secondary.client.anthropic

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.secondary.client.anthropic.support.SseEvent
import com.suarvis.butler.secondary.client.anthropic.support.SseParser
import com.suarvis.butler.secondary.config.AnthropicProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.stream.Collectors

/**
 * HTTP client for the Anthropic Messages API (streaming). Uses the JDK HTTP client and parses
 * Server-Sent Events itself, as Clawcode does: the wire format is small and stable, and owning it
 * keeps transcript conversion (tool results, thinking signatures) fully under our control.
 */
class AnthropicMessagesClient(
    private val properties: AnthropicProperties,
    httpClient: HttpClient? = null,
) {
    // Built on first use: every CLI invocation constructs this client, most never call a model.
    private val http: HttpClient by lazy { httpClient ?: HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build() }

    fun stream(body: JsonObject, onEvent: (SseEvent) -> Unit) {
        if (properties.apiKey.isBlank()) {
            throw failure(Failure.NOT_CONFIGURED, "No Anthropic API key: set ANTHROPIC_API_KEY (or butler.providers.anthropic.api-key)")
        }
        val request =
            HttpRequest.newBuilder(URI.create(properties.baseUrl.trimEnd('/') + MESSAGES_PATH))
                .timeout(properties.requestTimeout)
                .header("x-api-key", properties.apiKey)
                .header("anthropic-version", properties.apiVersion)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofLines())
            } catch (error: IOException) {
                throw failure(Failure.TRANSPORT, "Could not reach ${properties.baseUrl}: ${error.message}", cause = error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure(Failure.TRANSPORT, "Interrupted while calling Anthropic", cause = error)
            }
        response.body().use { lines ->
            if (response.statusCode() !in 200..299) {
                val text = lines.collect(Collectors.joining("\n"))
                throw httpFailure(response.statusCode(), text, response.headers().firstValue("retry-after").orElse(null))
            }
            try {
                SseParser.parse(lines.iterator(), onEvent)
            } catch (error: UncheckedIOException) {
                throw failure(Failure.TRANSPORT, "Connection to Anthropic broke mid-response: ${error.message}", cause = error)
            }
        }
    }

    private fun httpFailure(status: Int, body: String, retryAfter: String?): ModelProviderException {
        val reason = errorMessage(body) ?: "HTTP $status"
        val kind =
            when (status) {
                401, 403 -> Failure.AUTHENTICATION
                429 -> Failure.RATE_LIMITED
                529, 503 -> Failure.OVERLOADED
                in 400..499 -> Failure.INVALID_REQUEST
                else -> Failure.SERVER_ERROR
            }
        return failure(kind, "Anthropic API error ($status): $reason", retryAfterSeconds = retryAfter?.trim()?.toLongOrNull())
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
        cause: Throwable? = null,
    ) = ModelProviderException(ProviderKind.ANTHROPIC, kind, message, retryAfterSeconds, cause)

    private companion object {
        const val MESSAGES_PATH = "/v1/messages"
    }
}
