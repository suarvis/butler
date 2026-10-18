package com.suarvis.butler.secondary.persistence.file.repository

import com.suarvis.butler.secondary.persistence.file.entity.SessionLineRecord
import com.suarvis.butler.secondary.persistence.file.support.SessionJsonl
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** Reads and writes `<workspace>/<directory>/<session-id>.jsonl` files. Knows nothing about the domain. */
class SessionFileRepository(private val directory: String) {
    fun sessionsDirectory(workspaceRoot: Path): Path = workspaceRoot.resolve(directory)

    fun read(workspaceRoot: Path, sessionId: String): List<SessionLineRecord>? {
        val file = fileFor(workspaceRoot, sessionId)
        return if (file.isRegularFile()) readFile(file) else null
    }

    /** Every session file in the workspace, keyed by session id (the file name without extension). */
    fun readAll(workspaceRoot: Path): Map<String, List<SessionLineRecord>> {
        val dir = sessionsDirectory(workspaceRoot)
        if (!dir.exists()) return emptyMap()
        return dir.listDirectoryEntries("*.$EXTENSION")
            .filter { it.isRegularFile() }
            .associate { it.name.removeSuffix(".$EXTENSION") to readFile(it) }
    }

    /**
     * Replaces the file atomically: a crash mid-write leaves either the old or the new version,
     * never a truncated transcript.
     */
    fun write(workspaceRoot: Path, sessionId: String, records: List<SessionLineRecord>) {
        val target = fileFor(workspaceRoot, sessionId)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".$sessionId", ".tmp")
        try {
            temporary.writeText(records.joinToString(separator = "\n", postfix = "\n") { SessionJsonl.encode(it) })
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notSupported: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun delete(workspaceRoot: Path, sessionId: String): Boolean = Files.deleteIfExists(fileFor(workspaceRoot, sessionId))

    fun fileFor(workspaceRoot: Path, sessionId: String): Path = sessionsDirectory(workspaceRoot).resolve("$sessionId.$EXTENSION")

    private fun readFile(file: Path): List<SessionLineRecord> =
        file.readLines().mapIndexedNotNull { index, line ->
            try {
                SessionJsonl.decode(line)
            } catch (error: IllegalArgumentException) {
                throw MalformedSessionFileException(file, index + 1, error)
            }
        }

    private companion object {
        const val EXTENSION = "jsonl"
    }
}

class MalformedSessionFileException(val file: Path, val line: Int, cause: Throwable) :
    RuntimeException("Invalid session record in $file at line $line: ${cause.message}", cause)
