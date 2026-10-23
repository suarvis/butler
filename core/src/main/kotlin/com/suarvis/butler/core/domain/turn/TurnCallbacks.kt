package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.BashRisk
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolInvocation

/**
 * Everything the caller of a turn supplies; later additions extend this class instead of changing
 * execute again. Each default is the safe answer for a run nobody can be asked about: stop at a
 * limit, deny a tool call, never cancel.
 */
data class TurnCallbacks(
    val observer: TurnObserver = TurnObserver.NONE,
    val approver: TurnApprover = TurnApprover.STOP,
    val toolApprover: ToolApprover = ToolApprover.DENY,
    val cancellation: TurnCancellation = TurnCancellation.NEVER,
)

/**
 * Asked when the permission policy says a tool call needs approval. Like [TurnApprover], core
 * only states what it needs to know; the CLI prompts a person, scripts answer from flags.
 */
fun interface ToolApprover {
    fun decide(request: ToolApprovalRequest): ToolApprovalDecision

    companion object {
        /** For runs nobody can be asked about: never run a tool that needs approval. */
        val DENY = ToolApprover { ToolApprovalDecision.DENY }
    }
}

/**
 * One tool call awaiting approval: the call as the model sent it, its decoded input, the
 * permission it needs, the mode the session runs in and why the policy asks.
 *
 * @param preview for people, not the model: a diff for edits, the command for bash.
 */
data class ToolApprovalRequest(
    val call: ContentBlock.ToolUse,
    val invocation: ToolInvocation,
    val tier: PermissionTier,
    val mode: PermissionMode,
    val reason: String,
    val preview: String?,
    val bashRisk: BashRisk? = null,
)

enum class ToolApprovalDecision {
    ALLOW_ONCE,

    /** Allow this tool for the rest of the session without asking again. */
    ALLOW_FOR_SESSION,
    DENY,
}

/** Turn settings bound from configuration (butler.tools.*), since core cannot read configuration. Lane A adds the iteration limit here. */
data class TurnSettings(
    /** Off by default, so a turn offers the model no tools until they are switched on. */
    val toolsEnabled: Boolean = false,
)
