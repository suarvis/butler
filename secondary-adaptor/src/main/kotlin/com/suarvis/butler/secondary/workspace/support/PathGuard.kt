package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Real-path containment, the check [WorkspacePath.within] cannot make: that check is lexical, and
 * a symlink inside the workspace can still lead outside it. As in Clawcode's
 * `validate_workspace_boundary`, a path is inside when its real path starts with the workspace's
 * real path.
 */
internal class PathGuard(private val workspace: WorkspaceRoot) {
    /** Clawcode's `canonicalize_workspace_root`: the real root, or the root as given when it cannot be resolved. */
    val root: Path =
        try {
            workspace.path.toRealPath()
        } catch (unresolvable: IOException) {
            workspace.path
        }

    /**
     * The real path of an existing file or folder.
     *
     * @throws IOException when it does not exist or cannot be resolved
     * @throws WorkspaceToolException when a symlink leads it outside the workspace
     */
    fun existing(path: WorkspacePath): Path = requireInside(resolve(path).toRealPath())

    /**
     * Where writing [path] lands: its real path when it exists, otherwise the real path of its
     * nearest existing folder plus the names still missing. A symlink is followed to the file it
     * leads to, also when that file does not exist yet. Clawcode's `normalize_path_allow_missing`
     * keeps a dangling link as it is, and `fs::write` then creates the link's target wherever it
     * points; here that target is checked like any other path.
     *
     * @throws IOException when the path cannot be resolved, such as through a loop of links
     * @throws WorkspaceToolException when the place it lands lies outside the workspace
     */
    fun forWrite(path: WorkspacePath): Path = requireInside(landing(resolve(path), links = 0))

    /** [path] on disk, as spelled: relative to the workspace root, with no links resolved. */
    fun resolve(path: WorkspacePath): Path = if (path == WorkspacePath.ROOT_PATH) workspace.path else workspace.path.resolve(path.value)

    /** Whether a real (or already normalised) [path] lies inside the workspace. */
    fun contains(path: Path): Boolean = path.startsWith(root)

    /** @throws WorkspaceToolException with Clawcode's wording when [path] lies outside the workspace */
    fun requireInside(path: Path): Path {
        if (!contains(path)) throw WorkspaceToolException("path $path escapes workspace boundary $root")
        return path
    }

    /** [file], a real path inside the workspace, relative to it with `/` between names, as diffs label files. */
    fun label(file: Path): String = root.relativize(file).joinToString("/")

    private fun landing(spelled: Path, links: Int): Path {
        try {
            return spelled.toRealPath()
        } catch (missing: NoSuchFileException) {
            // Something on the way does not exist yet: the file, a folder above it, or a link's target.
        }
        val parent = spelled.parent ?: throw NoSuchFileException(spelled.toString())
        if (Files.isSymbolicLink(spelled)) {
            if (links >= MAX_LINKS) throw FileSystemException(spelled.toString(), null, TOO_MANY_LINKS)
            return landing(parent.resolve(Files.readSymbolicLink(spelled)), links + 1)
        }
        val name = spelled.fileName.toString()
        // The system refuses `missing/..`, and creating the folders first would let the `..` lead anywhere.
        if (name == "." || name == "..") throw NoSuchFileException(spelled.toString())
        return landing(parent, links).resolve(name)
    }

    private companion object {
        /** Linux's `MAXSYMLINKS`: links followed for one path before giving up. */
        const val MAX_LINKS = 40

        /** Rust's text for `ELOOP`. */
        const val TOO_MANY_LINKS = "Too many levels of symbolic links (os error 40)"
    }
}
