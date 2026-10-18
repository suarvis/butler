package com.suarvis.butler.api.cli.exception

import java.nio.file.Path

/** The user asked for something the CLI cannot do as written. Exit code 2. */
class CliUsageException(val kind: String, message: String, val hint: String? = null) : RuntimeException(message)

class InvalidWorkingDirectoryException(val path: Path) : RuntimeException("Working directory does not exist or is not a directory: $path")

class CliOutputPathException(val path: Path, cause: Throwable) : RuntimeException("Cannot write to $path: ${cause.message}", cause)
