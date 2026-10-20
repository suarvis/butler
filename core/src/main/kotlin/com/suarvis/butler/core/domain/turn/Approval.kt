package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.routing.RoutingObstacle

/** A decision the turn needs from the user: the model it was about to use, what stands in the way, and models that are clear to use instead. */
data class ApprovalRequest(
    val model: ModelProfile,
    val obstacles: List<RoutingObstacle>,
    val alternatives: List<ModelProfile>,
)

sealed interface ApprovalDecision {
    /** Use the model anyway: go over budget, or call the rate-limited provider again. */
    data object Continue : ApprovalDecision

    data class SwitchTo(val model: ModelProfile) : ApprovalDecision

    data object Stop : ApprovalDecision
}

/**
 * Asks whoever runs the turn. The CLI prompts a person at a terminal and answers for scripts from
 * flags; a later web adaptor would pause the turn instead. Core only states what it needs to know.
 */
fun interface TurnApprover {
    fun decide(request: ApprovalRequest): ApprovalDecision

    companion object {
        /** For runs nobody can be asked about: never go over a limit. */
        val STOP = TurnApprover { ApprovalDecision.Stop }
    }
}
