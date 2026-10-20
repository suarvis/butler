package com.suarvis.butler.api.cli.prompt

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.TurnApprover
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Answers a turn's approval requests the way `claw` answers permission prompts: a person at a
 * terminal is asked; a script is never asked, goes over budget only with --allow-over-budget,
 * and otherwise stops. Going over a budget, once approved, is not asked again for the rest of the
 * run, so a REPL session does not stop before every prompt.
 */
class CliTurnApprover(
    private val console: CliConsole,
    private val format: OutputFormat,
    private val allowOverBudget: Boolean,
    private val clock: Clock,
) : TurnApprover {
    private val approvedBudgets = mutableSetOf<BudgetScope>()

    override fun decide(request: ApprovalRequest): ApprovalDecision {
        val overBudget = request.obstacles.filterIsInstance<RoutingObstacle.OverBudget>().flatMap { it.breaches }
        val onlyBudgets = request.obstacles.all { it is RoutingObstacle.OverBudget }
        if (onlyBudgets && overBudget.all { it.budget.scope in approvedBudgets }) return ApprovalDecision.Continue
        if (onlyBudgets && allowOverBudget) {
            console.err.println("warning: ${overBudget.joinToString("; ") { it.describe() }}; continuing (--allow-over-budget)")
            console.err.flush()
            return ApprovalDecision.Continue
        }
        if (!console.interactive || format != OutputFormat.TEXT) return ApprovalDecision.Stop
        return ask(request, onlyBudgets)
    }

    private fun ask(request: ApprovalRequest, onlyBudgets: Boolean): ApprovalDecision {
        val out = console.out
        out.println()
        out.println("Approval needed before calling ${label(request.model)}")
        request.obstacles.forEach { out.println("  - ${describe(it)}") }
        val continueLabel = if (onlyBudgets) "continue over budget" else "try ${request.model.provider.id} anyway"
        val options =
            listOf("[c] $continueLabel") +
                request.alternatives.mapIndexed { index, model -> "[${index + 1}] switch to ${label(model)}" } +
                "[s] stop"
        out.println("  " + options.joinToString("   "))
        repeat(MAX_PROMPTS) {
            out.print("Choice [s]: ")
            out.flush()
            val answer = console.readLine()?.trim()?.lowercase() ?: return ApprovalDecision.Stop
            val chosen = answer.toIntOrNull()?.let { request.alternatives.getOrNull(it - 1) }
            when {
                answer == "c" || answer == "continue" -> {
                    if (onlyBudgets) {
                        request.obstacles.filterIsInstance<RoutingObstacle.OverBudget>().flatMap { it.breaches }.forEach { approvedBudgets += it.budget.scope }
                        out.println("Going over budget for the rest of this session.")
                    }
                    return ApprovalDecision.Continue
                }
                chosen != null -> {
                    out.println("Switched to ${label(chosen)}; the conversation goes along.")
                    return ApprovalDecision.SwitchTo(chosen)
                }
                answer.isEmpty() || answer == "s" || answer == "stop" -> return ApprovalDecision.Stop
                else -> out.println("Type c, s or the number of a model.")
            }
        }
        return ApprovalDecision.Stop
    }

    private fun describe(obstacle: RoutingObstacle): String =
        when (obstacle) {
            is RoutingObstacle.OverBudget -> obstacle.breaches.joinToString("; ") { it.describe() }
            is RoutingObstacle.RateLimited -> {
                val seconds = Duration.between(clock.instant(), obstacle.until).seconds.coerceAtLeast(1)
                "${obstacle.provider} is rate limited for another ${seconds}s"
            }
            is RoutingObstacle.Unavailable -> "${obstacle.provider} cannot serve requests: ${obstacle.reason}"
        }

    private fun label(model: ModelProfile): String = "${model.id} (${model.provider.id})"

    private companion object {
        const val MAX_PROMPTS = 3
    }
}

/** Builds one approver per CLI run (or REPL session), so remembered approvals last exactly that long. */
@Component
class CliTurnApprovers(
    private val console: CliConsole,
    private val clock: Clock,
) {
    fun create(invocation: CliInvocation): CliTurnApprover = CliTurnApprover(console, invocation.format, invocation.allowOverBudget, clock)
}
