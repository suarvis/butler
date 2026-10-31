package com.suarvis.butler.secondary.workspace.support

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/**
 * A failure the model can act on, such as a file that is binary or a pattern that does not
 * compile. The workspace gateway turns it into an error outcome, so it never reaches the agent
 * loop as an exception.
 */
internal class WorkspaceToolException(override val message: String) : RuntimeException(message)

/**
 * The text Clawcode reports for an I/O failure: Rust's `io::Error` display, `<reason> (os error
 * <code>)`, for the failures a file tool meets most. Others keep the JDK's own reason.
 */
internal object IoErrorMessages {
    /** What Rust reports when a directory is read as a file (`EISDIR`), as Clawcode's `read_file` does. */
    const val IS_A_DIRECTORY = "Is a directory (os error 21)"

    fun describe(error: IOException): String =
        when (error) {
            is NoSuchFileException -> "No such file or directory (os error 2)"
            is AccessDeniedException -> "Permission denied (os error 13)"
            is NotDirectoryException -> "Not a directory (os error 20)"
            is FileSystemException -> error.reason ?: error.message ?: error.javaClass.simpleName
            else -> error.message ?: error.javaClass.simpleName
        }
}
