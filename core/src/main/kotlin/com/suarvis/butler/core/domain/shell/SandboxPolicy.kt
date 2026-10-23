package com.suarvis.butler.core.domain.shell

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.generic.ensure
import java.nio.file.Path

/**
 * Where a shell command may write and whether it may reach the network. Butler decides it, never
 * the model, which is why the `bash` tool takes no sandbox overrides. Roots are absolute so the
 * adaptor that enforces them never resolves them against the wrong folder.
 */
data class SandboxPolicy(
    val writableRoots: List<Path>,
    val network: Boolean,
) {
    init {
        ensure(writableRoots.all { it.isAbsolute }) { "Sandbox writable roots must be absolute: $writableRoots" }
    }

    companion object {
        /** The default for a turn until lane F tunes it: write inside the workspace only, no network. */
        fun forWorkspace(root: WorkspaceRoot): SandboxPolicy = SandboxPolicy(listOf(root.path), network = false)
    }
}
