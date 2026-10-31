package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.budget.BillingPeriod
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.exception.BudgetExceededException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.model.TranscriptNormalizer
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.routing.RoutingContext
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.routing.RoutingPolicy
import com.suarvis.butler.core.domain.routing.TokenEstimator
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.SystemPromptComposer
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.domain.turn.TurnSettings
import com.suarvis.butler.core.domain.turn.TurnSummary
import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.McpGateway
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.core.gateway.PermissionRulesGateway
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * One user turn: record the prompt, then ask a model the user's budgets and rate limits allow, run
 * the tools it calls, send back the results and ask again, until it answers without calling any.
 * Each call's answer and cost are recorded.
 *
 * The loop keeps the order of Clawcode's `run_turn` (`conversation.rs`): call the model, save the
 * reply, stop if it calls no tools, otherwise run its calls one after another, in the order the
 * model made them, saving each result. The prompt, every reply and every result are saved as they
 * happen, so a failure or a crash never loses them and the transcript stays valid at every step.
 *
 * Tools are offered only when [TurnSettings.toolsEnabled]. While they are off a turn makes exactly
 * one model call, and any tool call the model makes anyway is answered with an error. Each call
 * passes the permission step in [RunToolCallUseCase], against the user's rules read once per turn.
 *
 * The turn polls [TurnCallbacks.cancellation] before each model call, before each tool call and
 * after each approval prompt, and while a reply streams. Once cancelled it stops at that step, as
 * Clawcode's does: a partly streamed reply is dropped, every call still pending gets an
 * "interrupted" result, the session is saved and the summary says [TurnSummary.interrupted].
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
    private val runToolCall: RunToolCallUseCase,
    private val mcp: McpGateway,
    private val permissionRules: PermissionRulesGateway,
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
        val cancellation = callbacks.cancellation
        val startedAt = clock.instant()
        val existing = command.session?.let { getSession.execute(command.workspace, it) }
        var session =
            (existing ?: Session.start(identity.nextSessionId(), command.workspace, null, startedAt))
                .closeDanglingToolCalls(INTERRUPTED_TOOL_RESULT, startedAt)

        // Resolved before anything is saved, so an unknown model never leaves a half-started turn.
        val requested = catalog.resolve(command.model ?: session.model ?: catalog.defaultModel())
        // The canonical id is stored, not the alias, as Clawcode does: a session keeps its model when an alias moves on.
        if (session.model != requested.id) session = session.switchModel(requested.id, startedAt)

        val offered = offeredTools(command.allowedTools)
        // Read once, so every call in the turn is judged by the same rules.
        val rules = permissionRules.rules()

        session = session.appendUserPrompt(command.prompt, startedAt)
        sessions.save(session)
        observer.onEvent(TurnEvent.Started(session.id, requested.id, newSession = existing == null))

        var model = requested
        val replies = mutableListOf<ConversationMessage>()
        var turnUsage = TokenUsage.ZERO
        var iterations = 0
        // The last reply's; a turn cancelled before any reply has none, so it reports OTHER.
        var stopReason = StopReason.OTHER
        fun interrupted(): TurnSummary {
            sessions.save(session)
            val summary = TurnSummary(session, model, replies, iterations, turnUsage, stopReason, interrupted = true)
            observer.onEvent(TurnEvent.Completed(summary))
            return summary
        }
        while (true) {
            // Clawcode checks before counting the iteration, so a turn stopped here made no call for it.
            if (cancellation.isCancelled()) return interrupted()
            iterations++
            val answered = answer(session, model, command.workspace, offered, observer, approver, cancellation)
            // A switch the user approved holds for the rest of the turn.
            model = answered.model
            session = answered.session
            val response = answered.response ?: return interrupted()
            stopReason = response.stopReason
            val reply = response.toAssistantMessage()
            val answeredAt = clock.instant()
            session = session.appendAssistantMessage(reply, answeredAt)
            if (!settings.toolsEnabled) {
                // No tools were offered; answering any stray call keeps the transcript valid.
                session = session.closeDanglingToolCalls(TOOLS_UNAVAILABLE_RESULT, answeredAt)
            }
            sessions.save(session)
            usage.record(
                UsageRecord(answeredAt, model.provider.id, model.id, response.usage, model.pricing?.costOf(response.usage)),
            )
            replies += reply
            turnUsage += response.usage
            session = afterReplySaved(session)

            if (!settings.toolsEnabled || reply.toolUses.isEmpty()) {
                val summary = TurnSummary(session, model, replies, iterations, turnUsage, stopReason)
                observer.onEvent(TurnEvent.Completed(summary))
                return summary
            }
            val ran = runPendingToolCalls(session, command, offered, rules, callbacks)
            session = ran.session
            if (ran.interrupted) return interrupted()
            // A cancel during the last call's approval prompt is caught by the poll before the next model call.
        }
    }

    /**
     * The tools this turn offers, read once so the list stays the same for every call in it:
     * the built-in tools, then the MCP servers' tools, narrowed by --allowedTools when given.
     */
    private fun offeredTools(allowedTools: Set<ToolName>?): List<ToolDefinition> {
        if (!settings.toolsEnabled) return emptyList()
        // A name can be offered only once; a built-in keeps its name over an MCP tool that reuses it.
        val all = (BuiltInTools.ALL + mcp.tools()).distinctBy { it.name }
        return if (allowedTools == null) all else all.filter { it.name in allowedTools }
    }

    /**
     * Lane G's hook: the auto-compaction check runs here, after every saved reply, the last one
     * included, as Clawcode's `maybe_auto_compact` does. Until then the session passes unchanged.
     */
    private fun afterReplySaved(session: Session): Session = session

    private class Ran(val session: Session, val interrupted: Boolean)

    /**
     * Runs the latest reply's calls one at a time, in the order the model made them, as Clawcode
     * does, saving after each result. A tool this turn did not offer still gets a result (an
     * error), so every call is answered.
     *
     * Cancellation is polled before each call. Once the turn is cancelled, that call and every one
     * after it get Clawcode's "interrupted" result without running, so the transcript stays valid;
     * like Clawcode, each is still announced and finished, so the screen shows what was skipped.
     */
    private fun runPendingToolCalls(
        start: Session,
        command: Command,
        offered: List<ToolDefinition>,
        rules: PermissionRules,
        callbacks: TurnCallbacks,
    ): Ran {
        val offeredByName = offered.associateBy { it.name }
        var session = start
        var interrupted = false
        while (true) {
            val call = session.pendingToolCalls().firstOrNull() ?: return Ran(session, interrupted)
            callbacks.observer.onEvent(TurnEvent.ToolStarted(call))
            if (!interrupted) interrupted = callbacks.cancellation.isCancelled()
            val result =
                if (interrupted) {
                    ContentBlock.ToolResult(call.id, call.name, RunToolCallUseCase.INTERRUPTED_RESULT, isError = true)
                } else {
                    runToolCall.execute(
                        RunToolCallUseCase.Command(command.workspace, call, offeredByName[call.name], command.permissionMode, rules, callbacks.cancellation),
                        callbacks.toolApprover,
                    )
                }
            session = session.recordToolResult(result, clock.instant())
            sessions.save(session)
            callbacks.observer.onEvent(TurnEvent.ToolFinished(call, result))
        }
    }

    /** [response] is null when the turn was cancelled before or while the model answered. */
    private class Answered(val session: Session, val model: ModelProfile, val response: ModelResponse?)

    /** Thrown from the stream's event callback to stop a reply once the turn is cancelled; never leaves this class. */
    private class TurnInterrupted : RuntimeException("turn interrupted", null, false, false)

    /**
     * Calls the model the routing rules allow, switching or retrying when the user approves.
     * Bounded, so an approver that keeps saying "try again" cannot loop forever.
     */
    private fun answer(
        start: Session,
        requested: ModelProfile,
        workspace: WorkspaceRoot,
        offered: List<ToolDefinition>,
        observer: TurnObserver,
        approver: TurnApprover,
        cancellation: TurnCancellation,
    ): Answered {
        var session = start
        // Earlier calls of an offered tool stay native tool calls; the rest are sent as text.
        val transcript = TranscriptNormalizer.normalize(session.messages, offered.map { it.name }.toSet())
        val estimatedInputTokens = TokenEstimator.estimate(systemPrompt(workspace, requested), transcript)
        var model = approvedModel(requested, estimatedInputTokens, approver)
        var lastFailure: ModelProviderException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (session.model != model.id) {
                session = session.switchModel(model.id, clock.instant())
                sessions.save(session)
            }
            // A retry follows an approval prompt, during which the turn may have been cancelled.
            if (attempt > 0 && cancellation.isCancelled()) return Answered(session, model, null)
            try {
                return Answered(session, model, call(model, workspace, transcript, offered, observer, cancellation))
            } catch (interrupted: TurnInterrupted) {
                // Whatever streamed so far is dropped: a partial reply is never saved.
                return Answered(session, model, null)
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

    /**
     * One streamed model call. Cancellation is polled on every event; once cancelled, the callback
     * throws [TurnInterrupted], which the client is expected to let through after closing its stream.
     *
     * @throws TurnInterrupted when the turn was cancelled during the call. A failure while cancelled
     *   counts as that too: Clawcode treats it as the client giving up because of the interrupt.
     */
    private fun call(
        model: ModelProfile,
        workspace: WorkspaceRoot,
        transcript: List<ConversationMessage>,
        offered: List<ToolDefinition>,
        observer: TurnObserver,
        cancellation: TurnCancellation,
    ): ModelResponse {
        var stopped = false
        val response =
            try {
                models.stream(ModelRequest(model, systemPrompt(workspace, model), transcript, tools = offered)) { event ->
                    if (stopped || cancellation.isCancelled()) {
                        stopped = true
                        throw TurnInterrupted()
                    }
                    observer.onEvent(
                        when (event) {
                            is ModelStreamEvent.TextDelta -> TurnEvent.TextDelta(event.text)
                            is ModelStreamEvent.ThinkingDelta -> TurnEvent.ThinkingDelta(event.text)
                        },
                    )
                }
            } catch (interrupted: TurnInterrupted) {
                throw interrupted
            } catch (failure: RuntimeException) {
                if (stopped || cancellation.isCancelled()) throw TurnInterrupted()
                throw failure
            }
        // A client that swallowed the callback's exception returns only part of the reply.
        if (stopped) throw TurnInterrupted()
        return response
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
        val DEFAULT_COOLDOWN: Duration = Duration.ofSeconds(60)
        const val MAX_ATTEMPTS = 5
        const val INTERRUPTED_TOOL_RESULT = "Interrupted before this tool call completed"
        const val TOOLS_UNAVAILABLE_RESULT = "Tools are not available in this version of Butler"
    }
}
