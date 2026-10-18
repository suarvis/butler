package com.suarvis.butler.core.domain.exception

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

/** A secondary adaptor could not complete a gateway call (storage, network, ...). */
class GatewayException(message: String, cause: Throwable? = null) : ButlerException(message, cause)
