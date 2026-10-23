package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.budget.BillingPeriod
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.exception.BudgetExceededException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.model.TranscriptNormalizer
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.routing.RoutingContext
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.routing.RoutingPolicy
import com.suarvis.butler.core.domain.routing.TokenEstimator
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.SystemPromptComposer
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.domain.turn.TurnSettings
import com.suarvis.butler.core.domain.turn.TurnSummary
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * One user turn: record the prompt, choose a model the user's budgets and rate limits allow, ask
 * it, record the answer and what it cost.
 *
 * The prompt is saved before the model is called, as Clawcode does, so a failed or interrupted
 * call never loses what the user typed. Tool execution and the model ↔ tool loop arrive in the
 * tools slice; until then the model is not offered any tools.
 *
 * The model may differ from the one that wrote earlier messages, even its provider: the
 * transcript is normalized for each request, so a session can move between providers. When the
 * chosen model is over budget, rate limited or out of quota, the caller's [TurnApprover] (in
 * [TurnCallbacks]) decides: go ahead anyway, switch to one of the clear fallbacks, or stop.
 */
class RunTurnUseCase(
    private val getSession: GetSessionUseCase,
    private val sessions: SessionGateway,
    private val identity: IdentityGateway,
    private val models: ModelGateway,
    private val catalog: ModelCatalogGateway,
    private val usage: UsageGateway,
    private val rateLimits: RateLimitGateway,
    private val budgets: BudgetGateway,
    private val clock: Clock,
    val settings: TurnSettings = TurnSettings(),
) {
    /**
     * @param session continue this saved session; null starts a new one.
     * @param model override for this turn; otherwise the session's model, otherwise the default.
     * @param allowedTools null means every tool; --allowedTools narrows it.
     */
    data class Command(
        val workspace: WorkspaceRoot,
        val prompt: String,
        val session: SessionReference? = null,
        val model: ModelId? = null,
        val permissionMode: PermissionMode = PermissionMode.PROMPT,
        val allowedTools: Set<ToolName>? = null,
    )

    fun execute(
        command: Command,
        callbacks: TurnCallbacks = TurnCallbacks(),
    ): TurnSummary {
        val observer = callbacks.observer
        val approver = callbacks.approver
        val startedAt = clock.instant()
        val existing = command.session?.let { getSession.execute(command.workspace, it) }
        var session =
            (existing ?: Session.start(identity.nextSessionId(), command.workspace, null, startedAt))
                .closeDanglingToolCalls(INTERRUPTED_TOOL_RESULT, startedAt)

        // Resolved before anything is saved, so an unknown model never leaves a half-started turn.
        val requested = catalog.resolve(command.model ?: session.model ?: catalog.defaultModel())
        // The canonical id is stored, not the alias, as Clawcode does: a session keeps its model when an alias moves on.
        if (session.model != requested.id) session = session.switchModel(requested.id, startedAt)

        session = session.appendUserPrompt(command.prompt, startedAt)
        sessions.save(session)
        observer.onEvent(TurnEvent.Started(session.id, requested.id, newSession = existing == null))

        val answered = answer(session, requested, command.workspace, observer, approver)
        val answer = answered.response.toAssistantMessage()
        val answeredAt = clock.instant()
        session =
            answered.session.appendAssistantMessage(answer, answeredAt)
                // No tools are offered yet; answering any stray call keeps the transcript valid.
                .closeDanglingToolCalls(TOOLS_UNAVAILABLE_RESULT, answeredAt)
        sessions.save(session)
        usage.record(
            UsageRecord(answeredAt, answered.model.provider.id, answered.model.id, answered.response.usage, answered.model.pricing?.costOf(answered.response.usage)),
        )

        val summary =
            TurnSummary(session, answered.model, listOf(answer), iterations = 1, usage = answered.response.usage, stopReason = answered.response.stopReason)
        observer.onEvent(TurnEvent.Completed(summary))
        return summary
    }

    private class Answered(val session: Session, val model: ModelProfile, val response: ModelResponse)

    /**
     * Calls the model the routing rules allow, switching or retrying when the user approves.
     * Bounded, so an approver that keeps saying "try again" cannot loop forever.
     */
    private fun answer(
        start: Session,
        requested: ModelProfile,
        workspace: WorkspaceRoot,
        observer: TurnObserver,
        approver: TurnApprover,
    ): Answered {
        var session = start
        val transcript = TranscriptNormalizer.normalize(session.messages, OFFERED_TOOLS)
        val estimatedInputTokens = TokenEstimator.estimate(systemPrompt(workspace, requested), transcript)
        var model = approvedModel(requested, estimatedInputTokens, approver)
        var lastFailure: ModelProviderException? = null
        repeat(MAX_ATTEMPTS) {
            if (session.model != model.id) {
                session = session.switchModel(model.id, clock.instant())
                sessions.save(session)
            }
            try {
                return Answered(session, model, call(model, workspace, transcript, observer))
            } catch (failure: ModelProviderException) {
                lastFailure = failure
                model = afterFailure(model, failure, estimatedInputTokens, approver)
            }
        }
        throw lastFailure!!
    }

    /** The requested model if nothing stands in its way, otherwise whatever the user approves. */
    private fun approvedModel(
        requested: ModelProfile,
        estimatedInputTokens: Long,
        approver: TurnApprover,
    ): ModelProfile {
        val context = routingContext(estimatedInputTokens)
        var candidate = requested
        repeat(MAX_ATTEMPTS) {
            val obstacles = RoutingPolicy.obstacles(candidate, context)
            if (obstacles.isEmpty()) return candidate
            val request = ApprovalRequest(candidate, obstacles, RoutingPolicy.alternatives(candidate, fallbackProfiles(), context))
            when (val decision = approver.decide(request)) {
                ApprovalDecision.Continue -> return candidate
                is ApprovalDecision.SwitchTo -> candidate = decision.model
                ApprovalDecision.Stop -> throw stopped(obstacles, context)
            }
        }
        val remaining = RoutingPolicy.obstacles(candidate, context)
        if (remaining.isEmpty()) return candidate
        throw stopped(remaining, context)
    }

    /**
     * A rate limit is remembered so other runs avoid the provider until it ends. Quota and overload
     * are not: the next call reports them again. Either way the user may retry, switch or stop.
     */
    private fun afterFailure(
        model: ModelProfile,
        failure: ModelProviderException,
        estimatedInputTokens: Long,
        approver: TurnApprover,
    ): ModelProfile {
        val obstacle =
            when (failure.failure) {
                Failure.RATE_LIMITED -> {
                    val until = clock.instant().plus(failure.retryAfterSeconds?.let(Duration::ofSeconds) ?: DEFAULT_COOLDOWN)
                    rateLimits.record(failure.provider, until)
                    RoutingObstacle.RateLimited(failure.provider, until)
                }
                Failure.QUOTA_EXHAUSTED, Failure.OVERLOADED ->
                    RoutingObstacle.Unavailable(failure.provider, failure.failure, failure.message ?: failure.failure.name)
                else -> throw failure
            }
        val context = routingContext(estimatedInputTokens)
        val alternatives = RoutingPolicy.alternatives(model, fallbackProfiles(), context, avoidProviders = setOf(failure.provider))
        return when (val decision = approver.decide(ApprovalRequest(model, listOf(obstacle), alternatives))) {
            ApprovalDecision.Continue -> model
            is ApprovalDecision.SwitchTo -> decision.model
            ApprovalDecision.Stop -> throw failure
        }
    }

    private fun call(
        model: ModelProfile,
        workspace: WorkspaceRoot,
        transcript: List<ConversationMessage>,
        observer: TurnObserver,
    ): ModelResponse =
        models.stream(ModelRequest(model, systemPrompt(workspace, model), transcript)) { event ->
            observer.onEvent(
                when (event) {
                    is ModelStreamEvent.TextDelta -> TurnEvent.TextDelta(event.text)
                    is ModelStreamEvent.ThinkingDelta -> TurnEvent.ThinkingDelta(event.text)
                },
            )
        }

    private fun systemPrompt(workspace: WorkspaceRoot, model: ModelProfile): String =
        SystemPromptComposer.compose(workspace, LocalDate.now(clock), model.id)

    private fun routingContext(estimatedInputTokens: Long): RoutingContext {
        val now = clock.instant()
        val period = BillingPeriod.containing(now, clock.zone)
        return RoutingContext(
            budgets = budgets.budgets(),
            usage = usage.summarize(period.start, period.end),
            cooldowns = rateLimits.activeCooldowns(now),
            now = now,
            estimatedInputTokens = estimatedInputTokens,
            isConfigured = catalog::isConfigured,
        )
    }

    /** Fallbacks that no longer resolve (a removed provider, a typo in configuration) are skipped, not fatal. */
    private fun fallbackProfiles(): List<ModelProfile> =
        catalog.fallbacks().mapNotNull { id ->
            try {
                catalog.resolve(id)
            } catch (unknown: UnknownModelException) {
                null
            }
        }

    private fun stopped(obstacles: List<RoutingObstacle>, context: RoutingContext): Exception {
        obstacles.filterIsInstance<RoutingObstacle.OverBudget>().firstOrNull()?.let { return BudgetExceededException(it.breaches) }
        val limited = obstacles.filterIsInstance<RoutingObstacle.RateLimited>().first()
        val remaining = Duration.between(context.now, limited.until).seconds.coerceAtLeast(1)
        return ModelProviderException(
            limited.provider,
            Failure.RATE_LIMITED,
            "${limited.provider} is rate limited for another ${remaining}s (remembered from an earlier 429)",
            retryAfterSeconds = remaining,
        )
    }

    private companion object {
        val OFFERED_TOOLS = emptySet<ToolName>()
        val DEFAULT_COOLDOWN: Duration = Duration.ofSeconds(60)
        const val MAX_ATTEMPTS = 5
        const val INTERRUPTED_TOOL_RESULT = "Interrupted before this tool call completed"
        const val TOOLS_UNAVAILABLE_RESULT = "Tools are not available in this version of Butler"
    }
}
