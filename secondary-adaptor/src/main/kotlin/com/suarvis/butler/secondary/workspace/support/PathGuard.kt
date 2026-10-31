package com.suarvis.butler.secondary.workspace.support

import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import java.io.IOException
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

    /** [path] on disk, as spelled: relative to the workspace root, with no links resolved. */
    fun resolve(path: WorkspacePath): Path = if (path == WorkspacePath.ROOT_PATH) workspace.path else workspace.path.resolve(path.value)

    /** Whether a real (or already normalised) [path] lies inside the workspace. */
    fun contains(path: Path): Boolean = path.startsWith(root)

    /** @throws WorkspaceToolException with Clawcode's wording when [path] lies outside the workspace */
    fun requireInside(path: Path): Path {
        if (!contains(path)) throw WorkspaceToolException("path $path escapes workspace boundary $root")
        return path
    }
}
