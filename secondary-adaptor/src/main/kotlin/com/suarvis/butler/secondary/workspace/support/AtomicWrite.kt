package com.suarvis.butler.secondary.workspace.support

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * Replaces a file's content so that an interrupted write leaves the old content or the new, never
 * part of each: the bytes go to a temp file in the target's own folder, which then takes the
 * target's place in one move, falling back to a plain replace where the file system cannot move
 * atomically, as `AtomicFiles` does for sessions. Departure from Clawcode, whose `write_file` and
 * `edit_file` truncate the file and write it in place with `fs::write`.
 *
 * The target must be a real path, so a symlink is written through to its target and never replaced
 * by a regular file. The new file keeps the old one's POSIX permissions, so a script stays
 * executable; a new file gets the permissions any new file gets (`0666` less the umask), as with
 * `fs::write`, rather than a temp file's owner-only ones. Because the file is replaced rather than
 * rewritten, it needs a writable folder, other hard links to it keep the old content, and it
 * belongs to the user Butler runs as.
 *
 * @param move puts the finished temp file in the target's place. A test seam: a test passes one that
 *   fails, as a crash during the move would.
 */
internal class AtomicWrite(
    private val move: (Path, Path) -> Unit = { temporary, target -> replace(temporary, target) },
) {
    /**
     * Writes [content] to [target], creating it when it does not exist. Its folder must exist.
     *
     * @throws AccessDeniedException when [target] exists but may not be written, as `fs::write`
     *   fails then; replacing it would otherwise succeed, since that needs only the folder
     * @throws IOException when the temp file cannot be written or moved; [target] is then unchanged
     */
    fun write(target: Path, content: ByteArray) {
        val exists = Files.exists(target)
        if (exists && !Files.isWritable(target)) throw AccessDeniedException(target.toString())
        val temporary = target.resolveSibling("$TEMP_PREFIX${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) channel.write(buffer)
                // On disk before the move, so a crash right after it cannot leave an empty file behind.
                channel.force(true)
            }
            if (exists) copyPermissions(target, temporary)
            move(temporary, target)
        } finally {
            removeLeftover(temporary)
        }
    }

    // Only where the file system has POSIX permissions; elsewhere the new file keeps its defaults.
    private fun copyPermissions(from: Path, to: Path) {
        val view = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
        Files.setPosixFilePermissions(to, view.readAttributes().permissions())
    }

    // After a move the temp file is gone already; after a failure it must not stay. A failure to
    // remove it must not hide the error that got here, so it is not reported.
    private fun removeLeftover(temporary: Path) {
        try {
            Files.deleteIfExists(temporary)
        } catch (stuck: IOException) {
            return
        }
    }

    companion object {
        /** Temp files are hidden and named for Butler, so one left by a killed process is recognisable. */
        const val TEMP_PREFIX = ".butler-write-"
        const val TEMP_SUFFIX = ".tmp"

        private fun replace(temporary: Path, target: Path) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notSupported: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
