package com.suarvis.butler.secondary.persistence.file.support

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.io.path.writeText

/** Replaces a file so that a crash mid-write leaves either the old or the new content, never a mix. */
object AtomicFiles {
    fun write(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.name}", ".tmp")
        try {
            temporary.writeText(content)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notSupported: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
