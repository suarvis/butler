package com.suarvis.butler.core.domain.generic

import java.nio.file.InvalidPathException

/**
 * A path inside the workspace: relative, normalised and `/`-separated; `.` is the workspace
 * itself. Every file tool goes through [within], so they all refuse `../` escapes the same way.
 *
 * The check is lexical only. Symlinks can still lead outside; only the adaptor that touches the
 * disk can see them, so it checks them before reading or writing.
 */
@JvmInline
value class WorkspacePath(val value: String) {
    init {
        ensure(value == ROOT || value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Not a normalised relative workspace path: '$value'"
        }
    }

    override fun toString(): String = value

    companion object {
        private const val ROOT = "."

        /** The workspace itself, for searches the model starts without a path. */
        val ROOT_PATH = WorkspacePath(ROOT)

        /**
         * [raw] as the model sent it, relative or absolute, made relative to [root]; null when it
         * leads outside the workspace or is not a usable path.
         */
        fun within(root: WorkspaceRoot, raw: String): WorkspacePath? {
            if (raw.isBlank() || '\u0000' in raw) return null
            val resolved =
                try {
                    root.path.resolve(raw).normalize()
                } catch (invalid: InvalidPathException) {
                    return null
                }
            if (!resolved.startsWith(root.path)) return null
            val relative = root.path.relativize(resolved)
            return if (relative.toString().isEmpty()) ROOT_PATH else WorkspacePath(relative.joinToString("/"))
        }
    }
}
