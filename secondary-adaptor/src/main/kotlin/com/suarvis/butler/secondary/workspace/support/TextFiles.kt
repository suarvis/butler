package com.suarvis.butler.secondary.workspace.support

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Reading workspace files as text with Clawcode's limits and checks (`runtime/src/file_ops.rs`). */
internal object TextFiles {
    /** Clawcode's `MAX_READ_SIZE`: `read_file` refuses a file over 10 MB rather than truncating it. */
    const val MAX_READ_BYTES: Long = 10L * 1024 * 1024

    /** Clawcode's `is_binary_file` reads one 8192-byte chunk and calls the file binary when it holds a NUL byte. */
    const val BINARY_PROBE_BYTES: Int = 8192

    /**
     * [file] as text for `read_file`, checked in Clawcode's order: the size from the file's
     * attributes before anything is read, then a NUL byte in the first 8 KB, then strict UTF-8,
     * as Rust's `read_to_string` decodes it.
     *
     * @throws WorkspaceToolException with Clawcode's wording when a check fails
     */
    fun readChecked(file: Path): String {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        if (attributes.isDirectory) throw WorkspaceToolException(IoErrorMessages.IS_A_DIRECTORY)
        val size = attributes.size()
        if (size > MAX_READ_BYTES) throw WorkspaceToolException("file is too large ($size bytes, max $MAX_READ_BYTES bytes)")
        val bytes = Files.readAllBytes(file)
        if ((0 until minOf(bytes.size, BINARY_PROBE_BYTES)).any { bytes[it] == NUL }) {
            throw WorkspaceToolException("file appears to be binary")
        }
        return decodeUtf8(bytes) ?: throw WorkspaceToolException("stream did not contain valid UTF-8")
    }

    /**
     * [file] as UTF-8 text, or null when it cannot be read or is not valid UTF-8. Clawcode's
     * `grep_search` skips such files, with no size or binary check.
     */
    fun readUtf8OrNull(file: Path): String? =
        try {
            decodeUtf8(Files.readAllBytes(file))
        } catch (unreadable: IOException) {
            null
        }

    /**
     * Rust's `str::lines`, which Clawcode splits files with: a line ends at `\n`, one `\r` right
     * before it is dropped, and a final line ending does not start another, empty line.
     */
    fun lines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                lines += text.substring(start)
                break
            }
            val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
            lines += text.substring(start, end)
            start = newline + 1
        }
        return lines
    }

    // Java's UTF-8 decoder keeps a byte order mark and rejects encoded surrogates, as Rust's does.
    private fun decodeUtf8(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (invalid: CharacterCodingException) {
            null
        }

    private const val NUL: Byte = 0
}
