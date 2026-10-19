package com.suarvis.butler.secondary.client.support

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderId
import java.io.IOException
import java.io.UncheckedIOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.stream.Collectors

/** A non-2xx reply, handed to the provider client so it can read its own error format. */
data class HttpErrorResponse(val status: Int, val body: String, val retryAfterSeconds: Long?)

/**
 * Sends a request whose response is a Server-Sent Events stream and feeds the events to the
 * caller as they arrive. Transport failures become [Failure.TRANSPORT]; HTTP errors are
 * translated by [onHttpError], because every provider words them differently.
 */
class EventStreamHttpClient(
    private val provider: ProviderId,
    connectTimeout: Duration,
    httpClient: HttpClient? = null,
) {
    // Built on first use: every CLI invocation constructs the clients, most never call a model.
    private val http: HttpClient by lazy { httpClient ?: HttpClient.newBuilder().connectTimeout(connectTimeout).build() }

    fun send(
        request: HttpRequest,
        onHttpError: (HttpErrorResponse) -> ModelProviderException,
        onEvent: (SseEvent) -> Unit,
    ) {
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofLines())
            } catch (error: IOException) {
                throw failure("Could not reach ${request.uri().scheme}://${request.uri().authority}: ${error.message}", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure("Interrupted while calling $provider", error)
            }
        response.body().use { lines ->
            if (response.statusCode() !in 200..299) {
                val body = lines.collect(Collectors.joining("\n"))
                val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toLongOrNull()
                throw onHttpError(HttpErrorResponse(response.statusCode(), body, retryAfter))
            }
            try {
                SseParser.parse(lines.iterator(), onEvent)
            } catch (error: UncheckedIOException) {
                throw failure("Connection to $provider broke mid-response: ${error.message}", error)
            }
        }
    }

    private fun failure(message: String, cause: Throwable) = ModelProviderException(provider, Failure.TRANSPORT, message, cause = cause)
}
