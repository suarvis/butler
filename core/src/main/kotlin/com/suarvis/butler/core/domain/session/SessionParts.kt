package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.generic.ensure
import java.time.Instant

/** Metadata about the latest compaction that replaced older messages with a summary. */
data class SessionCompaction(val count: Int, val removedMessageCount: Int, val summary: String) {
    init {
        ensure(count >= 1) { "Compaction count starts at 1" }
        ensure(removedMessageCount >= 0) { "Removed message count must not be negative" }
    }
}

/** Provenance of a session created by forking another one. */
data class SessionFork(val parentSessionId: SessionId, val branchName: BranchName?)

/** A user prompt with the time it was entered; kept separately so history survives compaction. */
data class PromptEntry(val enteredAt: Instant, val text: String)

/** Lightweight view of a session for listings, without the transcript. */
data class SessionSummary(
    val id: SessionId,
    val workspace: WorkspaceRoot,
    val model: ModelId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messageCount: Int,
    val fork: SessionFork?,
)
