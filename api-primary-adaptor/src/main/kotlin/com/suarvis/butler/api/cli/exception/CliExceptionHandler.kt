package com.suarvis.butler.api.cli.exception

import com.suarvis.butler.api.cli.CliEnvironment
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.dto.ErrorOutput
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.security.authorization.AuthorizationDeniedException
import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.exception.TranscriptIntegrityException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import picocli.CommandLine

/**
 * The CLI's counterpart of a web GlobalExceptionHandler: the only place that turns domain and
 * adaptor errors into an error kind, a message, a hint and an exit code.
 */
@Component
class CliExceptionHandler(
    private val output: CliOutputWriter,
    private val environment: CliEnvironment,
) {
    data class CliFailure(val kind: String, val message: String, val hint: String?, val exitCode: Int)

    fun handleParseError(error: CommandLine.ParameterException, args: List<String>): Int = report(classifyParseError(error), args)

    fun handleExecutionError(error: Throwable, args: List<String>): Int = report(classify(error), args)

    /** For the REPL: report an error in the session's output format without ending the process. */
    fun report(error: Throwable, format: OutputFormat): Int = write(classify(error), format)

    fun classify(error: Throwable): CliFailure =
        when (val cause = knownCause(error)) {
            is CliUsageException -> CliFailure(cause.kind, cause.messageOrName(), cause.hint, ExitCodes.USAGE)
            is CommandLine.ParameterException -> classifyParseError(cause)
            is InvalidWorkingDirectoryException ->
                CliFailure("invalid_cwd", cause.messageOrName(), "Pass an existing folder with --cwd PATH.", ExitCodes.RUNTIME)
            is CliOutputPathException -> CliFailure("invalid_output_path", cause.messageOrName(), null, ExitCodes.RUNTIME)
            is AuthorizationDeniedException ->
                CliFailure(cause.decision.kind, cause.decision.reason, cause.decision.hint, ExitCodes.RUNTIME)
            is ButlerException -> classifyDomain(cause)
            else -> {
                log.debug("Unexpected failure", error)
                CliFailure("internal", cause.messageOrName(), "Re-run with BUTLER_LOG_LEVEL=DEBUG for details.", ExitCodes.RUNTIME)
            }
        }

    private fun classifyDomain(error: ButlerException): CliFailure =
        when (error) {
            is DomainValidationException -> CliFailure("invalid_input", error.messageOrName(), null, ExitCodes.USAGE)
            is SessionNotFoundException ->
                CliFailure("session_not_found", error.messageOrName(), "Run `butler session list` to see saved sessions.", ExitCodes.RUNTIME)
            is TranscriptIntegrityException -> CliFailure("transcript_integrity", error.messageOrName(), null, ExitCodes.RUNTIME)
            is GatewayException ->
                CliFailure(
                    "persistence_error",
                    error.messageOrName(),
                    "Check the butler.persistence settings (BUTLER_PERSISTENCE_* environment variables).",
                    ExitCodes.RUNTIME,
                )
            is ModelProviderException -> classifyProvider(error)
        }

    private fun classifyProvider(error: ModelProviderException): CliFailure {
        val keyVariable = if (error.provider == ProviderKind.ANTHROPIC) "ANTHROPIC_API_KEY" else "the provider's API key setting"
        val (kind, hint) =
            when (error.failure) {
                Failure.NOT_CONFIGURED -> "missing_credentials" to "Set $keyVariable, or pick another model with --model."
                Failure.AUTHENTICATION -> "provider_authentication" to "Check that $keyVariable is valid."
                Failure.RATE_LIMITED ->
                    "rate_limited" to (error.retryAfterSeconds?.let { "Retry in ${it}s, or switch model with --model." } ?: "Retry shortly, or switch model with --model.")
                Failure.OVERLOADED -> "provider_overloaded" to "The provider is overloaded; retry shortly or switch model with --model."
                Failure.SERVER_ERROR -> "provider_error" to "Retry shortly; the provider reported an internal error."
                Failure.TRANSPORT -> "provider_unreachable" to "Check your network connection and the provider base URL."
                Failure.INVALID_REQUEST -> "provider_invalid_request" to "Re-run with BUTLER_LOG_LEVEL=DEBUG for details."
                Failure.INVALID_RESPONSE -> "provider_invalid_response" to "Re-run with BUTLER_LOG_LEVEL=DEBUG for details."
            }
        return CliFailure(kind, error.messageOrName(), hint, ExitCodes.RUNTIME)
    }

    private fun classifyParseError(error: CommandLine.ParameterException): CliFailure {
        val option = error.argSpec as? CommandLine.Model.OptionSpec
        val kind =
            when {
                option != null && OutputFormat.FLAG in option.names() -> "invalid_output_format"
                error is CommandLine.MissingParameterException -> "missing_argument"
                error is CommandLine.UnmatchedArgumentException -> "unknown_argument"
                else -> "cli_parse"
            }
        return CliFailure(kind, error.messageOrName(), DEFAULT_HINT, ExitCodes.USAGE)
    }

    private fun report(failure: CliFailure, args: List<String>): Int =
        write(failure, OutputFormat.detect(args, environment.variable(OutputFormat.ENV_VARIABLE)))

    private fun write(failure: CliFailure, format: OutputFormat): Int {
        val payload =
            ErrorOutput(
                kind = failure.kind,
                errorKind = failure.kind,
                error = failure.message,
                message = failure.message,
                hint = failure.hint,
                exitCode = failure.exitCode,
            )
        val text = "[error-kind: ${failure.kind}]\nerror: ${failure.message}\n\n${failure.hint ?: DEFAULT_HINT}"
        output.error(format, payload, text)
        return failure.exitCode
    }

    /** Spring wraps failures from lazily created beans; report the first cause we know how to explain. */
    private fun knownCause(error: Throwable): Throwable =
        generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
            .firstOrNull { it.isKnown() }
            ?: error

    private fun Throwable.isKnown(): Boolean =
        this is ButlerException ||
            this is CliUsageException ||
            this is CommandLine.ParameterException ||
            this is InvalidWorkingDirectoryException ||
            this is CliOutputPathException ||
            this is AuthorizationDeniedException

    private fun Throwable.messageOrName(): String = message ?: javaClass.simpleName

    private companion object {
        const val DEFAULT_HINT = "Run `butler --help` for usage."
        val log = LoggerFactory.getLogger(CliExceptionHandler::class.java)
    }
}
