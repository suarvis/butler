package com.suarvis.butler.secondary.workspace.support

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The folder walk under both searches, as Clawcode's `walkdir` does it by default: the start is
 * followed when it is a link, but nothing below it is, so a linked file or folder is skipped and a
 * search cannot leave the workspace through one. Each file found is still checked with [PathGuard]
 * and skipped when it lies outside; Clawcode fails the whole search instead, which would let one
 * stray link hide every other result.
 *
 * Folders named in [IGNORED_DIRECTORIES] are not entered. Departures from Clawcode, which prunes
 * them in `glob_search` only and there also prunes a start folder of that name: `grep_search` skips
 * them too, and the start folder is always searched, so a search the model aims at `dist` still
 * runs. Entries are visited in name order, where `walkdir` uses the file system's order, so results
 * are stable; a folder that cannot be listed is skipped.
 */
internal class SearchWalker(private val guard: PathGuard) {
    /**
     * Regular files at or below [start], spelled from [start] as Clawcode reports them. A start
     * that does not exist yields nothing, as with `walkdir`.
     *
     * @throws WorkspaceToolException when [start] resolves outside the workspace
     */
    fun files(start: Path): List<Path> {
        val real =
            try {
                start.toRealPath()
            } catch (missing: NoSuchFileException) {
                guard.requireInside(start.normalize())
                return emptyList()
            }
        guard.requireInside(real)
        val attributes = Files.readAttributes(real, BasicFileAttributes::class.java)
        return when {
            attributes.isRegularFile -> listOf(start)
            attributes.isDirectory -> ArrayList<Path>().also { walk(real, start, it) }
            else -> emptyList()
        }
    }

    private fun walk(directory: Path, spelled: Path, found: MutableList<Path>) {
        val children =
            try {
                Files.newDirectoryStream(directory).use { entries -> entries.sortedBy { it.fileName.toString() } }
            } catch (unlistable: IOException) {
                return
            } catch (unlistable: DirectoryIteratorException) {
                return
            }
        for (child in children) {
            val attributes =
                try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (gone: IOException) {
                    continue
                }
            val name = child.fileName.toString()
            when {
                attributes.isDirectory -> if (name !in IGNORED_DIRECTORIES) walk(child, spelled.resolve(name), found)
                attributes.isRegularFile -> if (isInside(child)) found.add(spelled.resolve(name))
            }
        }
    }

    private fun isInside(file: Path): Boolean =
        try {
            guard.contains(file.toRealPath())
        } catch (unresolvable: IOException) {
            false
        }

    companion object {
        /** Clawcode's `GLOB_SEARCH_IGNORED_DIRS`: version control, dependency and build output folders. */
        val IGNORED_DIRECTORIES: Set<String> = setOf(".git", "node_modules", ".build", "target", "dist", "coverage")
    }
}
