package com.suarvis.butler.core.domain.context

import com.suarvis.butler.core.domain.generic.ensure
import java.nio.file.Path

/**
 * What the system prompt tells the model about the project it works in, as Clawcode's
 * `ProjectContext` does: instruction files such as CLAUDE.md and a snapshot of git. Plain text
 * here; reading the disk and running git is the adaptor's job.
 */
data class ProjectContext(
    val instructions: List<InstructionFile> = emptyList(),
    val git: GitContext? = null,
) {
    companion object {
        /** No instruction files and no git repository: what a bare folder gives. */
        val EMPTY = ProjectContext()
    }
}

/** [path] and [scope] are absolute: instruction files can sit above the workspace, up to the repository root. [scope] is the folder the file applies to. */
data class InstructionFile(
    val path: Path,
    val scope: Path,
    val content: String,
) {
    init {
        ensure(path.isAbsolute && scope.isAbsolute) { "Instruction file paths must be absolute: $path applies to $scope" }
    }
}

/** A snapshot of the repository. [branch] is null on a detached HEAD; [status] is the working tree status as git prints it. */
data class GitContext(
    val branch: String?,
    val status: String,
    val recentCommits: List<GitCommit> = emptyList(),
    val stagedFiles: List<String> = emptyList(),
    val diff: String? = null,
)

data class GitCommit(
    val hash: String,
    val subject: String,
)
