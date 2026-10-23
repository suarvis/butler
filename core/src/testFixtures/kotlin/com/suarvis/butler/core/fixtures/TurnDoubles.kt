package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprover
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
