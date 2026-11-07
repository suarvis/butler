package com.suarvis.butler.secondary.workspace.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The temp-file-then-move write under `write_file` and `edit_file`, against real files in a temp folder. */
class AtomicWriteTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a missing file is created with the content`() {
        AtomicWrite().write(folder.resolve("new.txt"), "hello\n".toByteArray())

        assertEquals("hello\n", Files.readString(folder.resolve("new.txt")))
        assertEquals(listOf("new.txt"), entries())
    }

    @Test
    fun `an existing file is replaced whole and keeps its POSIX permissions`() {
        assumeTrue(folder.fileSystem.supportedFileAttributeViews().contains("posix"), "this file system has no POSIX permissions")
        val target = Files.writeString(folder.resolve("run.sh"), "a much longer old content\n")
        val permissions = PosixFilePermissions.fromString("rwxr-----")
        Files.setPosixFilePermissions(target, permissions)

        AtomicWrite().write(target, "short\n".toByteArray())

        assertEquals("short\n", Files.readString(target))
        assertEquals(permissions, Files.getPosixFilePermissions(target))
        assertEquals(listOf("run.sh"), entries())
    }

    @Test
    fun `a move that fails leaves the old content and removes the temp file`() {
        val target = Files.writeString(folder.resolve("notes.txt"), "old\n")
        var written: String? = null
        val crashing =
            AtomicWrite { temporary, _ ->
                written = Files.readString(temporary)
                throw IOException("interrupted")
            }

        val failure = runCatching { crashing.write(target, "new\n".toByteArray()) }.exceptionOrNull()

        assertEquals("interrupted", failure?.message)
        assertEquals("new\n", written)
        assertEquals("old\n", Files.readString(target))
        assertEquals(listOf("notes.txt"), entries())
    }

    @Test
    fun `the temp file sits beside the target and holds the whole content before the move`() {
        val target = folder.resolve("notes.txt")
        var seen: Pair<Path, String>? = null
        val inspecting =
            AtomicWrite { temporary, _ ->
                seen = temporary to Files.readString(temporary)
            }

        inspecting.write(target, "new\n".toByteArray())

        val (temporary, content) = checkNotNull(seen)
        assertEquals(folder, temporary.parent)
        assertTrue(temporary.fileName.toString().startsWith(AtomicWrite.TEMP_PREFIX), temporary.toString())
        assertEquals("new\n", content)
        // The move did nothing here, so the temp file was cleaned up rather than left beside the target.
        assertEquals(emptyList<String>(), entries())
    }

    @Test
    fun `a target in a missing folder fails without leaving anything behind`() {
        val failure = runCatching { AtomicWrite().write(folder.resolve("missing/new.txt"), "x".toByteArray()) }.exceptionOrNull()

        assertTrue(failure is NoSuchFileException, failure.toString())
        assertEquals(emptyList<String>(), entries())
    }

    @Test
    fun `a file that may not be written is refused, as writing it in place would be`() {
        assumeTrue(folder.fileSystem.supportedFileAttributeViews().contains("posix"), "this file system has no POSIX permissions")
        val target = Files.writeString(folder.resolve("locked.txt"), "old\n")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
        assumeTrue(!Files.isWritable(target), "these tests run as a user who may write any file")

        val failure = runCatching { AtomicWrite().write(target, "new\n".toByteArray()) }.exceptionOrNull()

        assertTrue(failure is AccessDeniedException, failure.toString())
        assertEquals("old\n", Files.readString(target))
        assertEquals(listOf("locked.txt"), entries())
    }

    private fun entries(): List<String> = Files.newDirectoryStream(folder).use { listing -> listing.map { it.fileName.toString() }.sorted() }
}
