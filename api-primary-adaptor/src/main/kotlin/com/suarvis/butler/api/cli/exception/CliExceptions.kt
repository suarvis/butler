package com.suarvis.butler.api.cli.exception

import java.nio.file.Path

/** The user asked for something the CLI cannot do as written. Exit code 2. */
class CliUsageException(val kind: String, message: String, val hint: String? = null) : RuntimeException(message)

class InvalidWorkingDirectoryException(val path: Path) : RuntimeException("Working directory does not exist or is not a directory: $path")

class CliOutputPathException(val path: Path, cause: Throwable) : RuntimeException("Cannot write to $path: ${cause.message}", cause)

/**
 * Ctrl-C stopped a one-shot prompt's turn. Exit code 130, error kind `interrupted`; Clawcode has
 * neither, so the wording is Butler's. The session was saved, so it can be continued.
 */
class CliInterruptedException(sessionId: String) : RuntimeException("turn interrupted by user (Ctrl-C)") {
    val hint: String = "The session was saved; continue it with `butler --resume $sessionId`."
}
