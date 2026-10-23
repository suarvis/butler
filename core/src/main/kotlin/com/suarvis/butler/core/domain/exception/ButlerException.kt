package com.suarvis.butler.core.domain.exception

import com.suarvis.butler.core.domain.budget.BudgetBreach
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.SessionReference

/**
 * Root of every error raised by business logic. Adaptors translate these into their own
 * protocol (CLI exit codes and error envelopes today, HTTP responses later); core never
 * knows how an error is presented.
 */
sealed class ButlerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A value object or entity was asked to hold a value that breaks its rules. */
class DomainValidationException(message: String) : ButlerException(message)

class SessionNotFoundException(val reference: SessionReference) :
    ButlerException("No saved session matches '${reference.describe()}'")

/**
 * The transcript would become invalid, for example a new prompt while tool calls are still
 * unanswered. Providers reject such transcripts, so the rule is enforced in the domain.
 */
class TranscriptIntegrityException(message: String) : ButlerException(message)

/** No configured provider can serve the requested model, so there is nothing to call. */
class UnknownModelException(val model: ModelId, reason: String) : ButlerException("Unknown model '$model': $reason")

/**
 * A tool call's input could not be decoded: malformed JSON, a missing or mistyped field, or a
 * path outside the workspace. The reason names the field that is wrong, so the model can be told
 * exactly what to fix.
 */
class InvalidToolInputException(val tool: ToolName, reason: String) : ButlerException("Invalid input for $tool: $reason")

/**
 * The turn stopped because a budget would be exceeded and the user did not approve going over
 * (or could not be asked, as in a script).
 */
class BudgetExceededException(val breaches: List<BudgetBreach>) :
    ButlerException("Over budget: ${breaches.joinToString("; ") { it.describe() }}")

/** A secondary adaptor could not complete a gateway call (storage, network, ...). */
class GatewayException(message: String, cause: Throwable? = null) : ButlerException(message, cause)

/**
 * A model provider call failed. [failure] tells callers (routing, the CLI) whether trying again
 * or switching provider can help, without exposing HTTP details.
 */
class ModelProviderException(
    val provider: ProviderId,
    val failure: Failure,
    message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : ButlerException(message, cause) {
    enum class Failure(val retryable: Boolean) {
        NOT_CONFIGURED(false),
        AUTHENTICATION(false),
        INVALID_REQUEST(false),
        INVALID_RESPONSE(false),
        RATE_LIMITED(true),

        /** The account is out of credit or quota; waiting does not help, switching provider might. */
        QUOTA_EXHAUSTED(false),
        OVERLOADED(true),
        SERVER_ERROR(true),
        TRANSPORT(true),
    }
}
