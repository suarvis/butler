package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprover
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.gateway.PermissionRulesGateway

/** Tool approver that records what it was asked and answers in order, denying once the answers run out. */
class RecordingToolApprover(vararg decisions: ToolApprovalDecision) : ToolApprover {
    private val pending = ArrayDeque(decisions.toList())
    val requests = mutableListOf<ToolApprovalRequest>()

    override fun decide(request: ToolApprovalRequest): ToolApprovalDecision {
        requests += request
        return pending.removeFirstOrNull() ?: ToolApprovalDecision.DENY
    }
}

class FixedPermissionRulesGateway(var rules: PermissionRules = PermissionRules.NONE) : PermissionRulesGateway {
    override fun rules(): PermissionRules = rules
}

/**
 * Budget and rate-limit approver that records what it was asked and answers each request with the
 * next of [answers], which may read the request (for example to pick one of its alternatives);
 * once they run out it stops, as a script nobody can ask does.
 */
class RecordingTurnApprover(vararg answers: (ApprovalRequest) -> ApprovalDecision) : TurnApprover {
    private val pending = ArrayDeque(answers.toList())
    val requests = mutableListOf<ApprovalRequest>()

    override fun decide(request: ApprovalRequest): ApprovalDecision {
        requests += request
        return pending.removeFirstOrNull()?.invoke(request) ?: ApprovalDecision.Stop
    }
}
