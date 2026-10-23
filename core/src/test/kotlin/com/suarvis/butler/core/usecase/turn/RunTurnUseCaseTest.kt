package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.exception.BudgetExceededException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.TurnApprover
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.fixtures.FixedBudgetGateway
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import com.suarvis.butler.core.fixtures.InMemoryRateLimitGateway
import com.suarvis.butler.core.fixtures.InMemorySessionGateway
import com.suarvis.butler.core.fixtures.InMemoryUsageGateway
import com.suarvis.butler.core.fixtures.MutableClock
import com.suarvis.butler.core.fixtures.ScriptedModelGateway
import com.suarvis.butler.core.fixtures.SequentialIdentityGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class RunTurnUseCaseTest {
    private val sessions = InMemorySessionGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val workspace = SessionFixtures.workspace
    private val catalog = FixedModelCatalog()
    private val usage = InMemoryUsageGateway()
    private val rateLimits = InMemoryRateLimitGateway()
    private val budgets = FixedBudgetGateway()

    private fun useCase(models: ScriptedModelGateway) =
        RunTurnUseCase(GetSessionUseCase(sessions), sessions, SequentialIdentityGateway(), models, catalog, usage, rateLimits, budgets, clock)

    private fun anthropicBudget(usd: String) = Budget(BudgetScope.Provider(ProviderId("anthropic")), BigDecimal(usd), null)

    private fun spentOnSonnet(usd: String, at: Instant = SessionFixtures.start) =
        usage.record(UsageRecord(at, ProviderId("anthropic"), ModelId("claude-sonnet-4-6"), TokenUsage(inputTokens = 1000), BigDecimal(usd)))

    /** Approver that records what it was asked and answers from a script. */
    private class ScriptedApprover(vararg decisions: (ApprovalRequest) -> ApprovalDecision) : TurnApprover {
        private val pending = ArrayDeque(decisions.toList())
        val requests = mutableListOf<ApprovalRequest>()

        override fun decide(request: ApprovalRequest): ApprovalDecision {
            requests += request
            return pending.removeFirst()(request)
        }
    }

    @Test
    fun `a first prompt starts a session and records prompt, answer and usage`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("Hello", " there"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Say hello"))

        val saved = sessions.findById(workspace, summary.session.id)!!
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), saved.messages.map { it.role })
        assertEquals("Hello there", summary.finalText)
        assertEquals(TokenUsage(inputTokens = 10, outputTokens = 5), saved.messages.last().usage)
        assertEquals(ModelId("claude-sonnet-4-6"), saved.model)
        assertEquals(1, summary.iterations)
    }

    @Test
    fun `the model receives the resolved model, a system prompt and the transcript`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("ok"))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "Explain", model = ModelId("opus")))

        val request = models.requests.single()
        assertEquals(ModelId("claude-opus-4-7"), request.model.id)
        assertEquals(ProviderKind.ANTHROPIC, request.model.provider.kind)
        assertTrue(request.systemPrompt.contains(workspace.toString()), request.systemPrompt)
        assertEquals(listOf(ConversationMessage.userText("Explain")), request.messages)
    }

    @Test
    fun `observers see start, deltas and completion in order`() {
        val events = mutableListOf<TurnEvent>()

        useCase(ScriptedModelGateway(ScriptedModelGateway.answer("a", "b"))).execute(
            RunTurnUseCase.Command(workspace, "go"),
            TurnCallbacks(observer = TurnObserver { events += it }),
        )

        assertTrue(events.first() is TurnEvent.Started && (events.first() as TurnEvent.Started).newSession)
        assertEquals(listOf(TurnEvent.TextDelta("a"), TurnEvent.TextDelta("b")), events.subList(1, 3))
        assertTrue(events.last() is TurnEvent.Completed)
    }

    @Test
    fun `continuing a session sends the whole transcript and keeps its model`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("first"), ScriptedModelGateway.answer("second"))
        val turns = useCase(models)
        val first = turns.execute(RunTurnUseCase.Command(workspace, "one", model = ModelId("haiku")))

        val second = turns.execute(RunTurnUseCase.Command(workspace, "two", session = SessionReference.ById(first.session.id)))

        assertEquals(first.session.id, second.session.id)
        assertEquals(3, models.requests.last().messages.size)
        assertEquals(ModelId("claude-haiku-4-5"), models.requests.last().model.id)
        assertEquals(4, second.session.messageCount)
    }

    @Test
    fun `an explicit model switches the session's model, stored by its canonical id`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("x"), ScriptedModelGateway.answer("y"))
        val turns = useCase(models)
        val first = turns.execute(RunTurnUseCase.Command(workspace, "one"))

        val second = turns.execute(RunTurnUseCase.Command(workspace, "two", SessionReference.Latest, ModelId("opus")))

        assertEquals(ModelId("claude-opus-4-7"), second.session.model)
        assertEquals(ModelId("claude-sonnet-4-6"), first.session.model)
    }

    @Test
    fun `a session can switch provider part-way and the new provider gets the whole conversation`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("from Claude"), ScriptedModelGateway.answer("from GPT"))
        val turns = useCase(models)
        turns.execute(RunTurnUseCase.Command(workspace, "one"))

        val second = turns.execute(RunTurnUseCase.Command(workspace, "two", SessionReference.Latest, ModelId("gpt-5.5")))

        val request = models.requests.last()
        assertEquals(FixedModelCatalog.OPENAI, request.model.provider)
        assertEquals(listOf("one", "from Claude", "two"), request.messages.map { it.text })
        assertEquals(ModelId("gpt-5.5"), sessions.findLatest(workspace)!!.model)
        assertEquals("from GPT", second.finalText)
    }

    @Test
    fun `tool history and thinking are sent as text while no tools are offered`() {
        sessions.save(SessionFixtures.sessionWithToolRoundTrip("session-tools"))
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("ok"))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "And now?", SessionReference.ById(SessionId("session-tools"))))

        val sent = models.requests.single().messages
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), sent.map { it.role })
        assertTrue(sent.flatMap { it.blocks }.all { it is ContentBlock.Text }, sent.toString())
        assertTrue(sent[1].text.contains("read_file"), sent[1].text)
        assertTrue(sent[2].text.contains("# Demo"), sent[2].text)
    }

    @Test
    fun `an unknown model fails before anything is saved or sent`() {
        val models = ScriptedModelGateway()

        assertThrows<UnknownModelException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "hi", model = ModelId("mystery-model"))) }

        assertTrue(models.requests.isEmpty())
        assertEquals(null, sessions.findLatest(workspace))
    }

    @Test
    fun `dangling tool calls from a crashed turn are closed before the new prompt`() {
        val call = ContentBlock.ToolUse(ToolCallId("toolu_9"), ToolName("bash"), JsonText("{}"))
        sessions.save(
            SessionFixtures.emptySession("session-crashed")
                .appendUserPrompt("run it", SessionFixtures.start)
                .appendAssistantMessage(ConversationMessage.assistant(listOf(call)), SessionFixtures.start),
        )
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("recovered"))

        val summary =
            useCase(models).execute(RunTurnUseCase.Command(workspace, "again", SessionReference.ById(SessionId("session-crashed"))))

        val roles = summary.session.messages.map { it.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.USER, MessageRole.ASSISTANT), roles)
        assertTrue(summary.session.messages[2].toolResults.single().isError)
    }

    @Test
    fun `a provider failure keeps the prompt and propagates the error`() {
        val failure = ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.RATE_LIMITED, "slow down", 30)
        val models = ScriptedModelGateway(ScriptedModelGateway.Reply.Failure(failure))

        val thrown = assertThrows<ModelProviderException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "keep me")) }

        assertEquals(30, thrown.retryAfterSeconds)
        val saved = sessions.findLatest(workspace)!!
        assertEquals(listOf("keep me"), saved.messages.map { it.text })
    }

    @Test
    fun `continuing a missing session fails before calling the model`() {
        val models = ScriptedModelGateway()

        assertThrows<SessionNotFoundException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "x", SessionReference.Latest)) }
        assertTrue(models.requests.isEmpty())
    }

    @Test
    fun `cost is estimated from the model's pricing and recorded in the usage ledger`() {
        val usageOfCall = TokenUsage(inputTokens = 1_000_000, outputTokens = 100_000)
        val summary = useCase(ScriptedModelGateway(ScriptedModelGateway.answer("x", usage = usageOfCall))).execute(RunTurnUseCase.Command(workspace, "go"))

        assertEquals(0, BigDecimal("4.5").compareTo(summary.estimatedCostUsd()))
        val record = usage.records.single()
        assertEquals(ProviderId("anthropic"), record.provider)
        assertEquals(ModelId("claude-sonnet-4-6"), record.model)
        assertEquals(usageOfCall, record.usage)
        assertEquals(0, BigDecimal("4.5").compareTo(record.costUsd))
    }

    @Test
    fun `over budget, a turn nobody can approve stops before calling the model`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        spentOnSonnet("1.00")
        val models = ScriptedModelGateway()

        val error = assertThrows<BudgetExceededException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "keep me")) }

        assertTrue(error.message!!.contains("anthropic monthly budget: $1.00 of $1.00"), error.message)
        assertTrue(models.requests.isEmpty())
        assertEquals(listOf("keep me"), sessions.findLatest(workspace)!!.messages.map { it.text })
    }

    @Test
    fun `spending from last month does not count against this month's budget`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        spentOnSonnet("5.00", at = Instant.parse("2026-09-30T23:00:00Z"))
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("fine"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "go"))

        assertEquals("fine", summary.finalText)
    }

    @Test
    fun `the user may approve going over budget`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        spentOnSonnet("2.00")
        val approver = ScriptedApprover({ ApprovalDecision.Continue })
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("over budget but approved"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "go"), TurnCallbacks(approver = approver))

        assertEquals("over budget but approved", summary.finalText)
        val obstacle = approver.requests.single().obstacles.single() as RoutingObstacle.OverBudget
        assertEquals(BudgetScope.Provider(ProviderId("anthropic")), obstacle.breaches.single().budget.scope)
        assertEquals(2, usage.records.size)
    }

    @Test
    fun `switching to an offered fallback sends the turn to that provider`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        spentOnSonnet("2.00")
        val approver = ScriptedApprover({ request -> ApprovalDecision.SwitchTo(request.alternatives.single()) })
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("from GPT"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "go"), TurnCallbacks(approver = approver))

        assertEquals(listOf(ModelId("gpt-5.5")), approver.requests.single().alternatives.map { it.id })
        assertEquals(FixedModelCatalog.OPENAI, models.requests.single().model.provider)
        assertEquals(ModelId("gpt-5.5"), summary.session.model)
        assertEquals(ModelId("gpt-5.5"), usage.records.last().model)
    }

    @Test
    fun `fallbacks without a key, on cooldown or over budget are not offered`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        spentOnSonnet("2.00")
        catalog.fallbackModels = listOf(ModelId("haiku"), ModelId("gpt-5.5"), ModelId("ollama/llama3.2"), ModelId("no-such-model"))
        catalog.unconfiguredProviders = setOf(ProviderId("openai"))
        rateLimits.record(ProviderId("ollama"), SessionFixtures.start.plusSeconds(30))
        val approver = ScriptedApprover({ ApprovalDecision.Stop })

        assertThrows<BudgetExceededException> { useCase(ScriptedModelGateway()).execute(RunTurnUseCase.Command(workspace, "go"), TurnCallbacks(approver = approver)) }

        assertTrue(approver.requests.single().alternatives.isEmpty(), approver.requests.single().alternatives.toString())
    }

    @Test
    fun `a remembered rate limit stops a turn nobody can approve without calling the provider`() {
        rateLimits.record(ProviderId("anthropic"), SessionFixtures.start.plusSeconds(40))
        val models = ScriptedModelGateway()

        val error = assertThrows<ModelProviderException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "go")) }

        assertEquals(ModelProviderException.Failure.RATE_LIMITED, error.failure)
        assertEquals(40, error.retryAfterSeconds)
        assertTrue(models.requests.isEmpty())
    }

    @Test
    fun `a 429 is remembered and the user can move the turn to another provider`() {
        val limited = ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.RATE_LIMITED, "slow down", retryAfterSeconds = 20)
        val models = ScriptedModelGateway(ScriptedModelGateway.Reply.Failure(limited), ScriptedModelGateway.answer("from GPT"))
        val approver = ScriptedApprover({ request -> ApprovalDecision.SwitchTo(request.alternatives.first()) })

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "go"), TurnCallbacks(approver = approver))

        assertEquals(SessionFixtures.start.plusSeconds(20), rateLimits.cooldowns[ProviderId("anthropic")])
        assertTrue(approver.requests.single().obstacles.single() is RoutingObstacle.RateLimited)
        assertEquals(listOf(ModelId("claude-sonnet-4-6"), ModelId("gpt-5.5")), models.requests.map { it.model.id })
        assertEquals(listOf("go"), models.requests.last().messages.map { it.text })
        assertEquals("from GPT", summary.finalText)
    }

    @Test
    fun `an exhausted quota offers other providers and retrying keeps the same model`() {
        val noQuota = ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.QUOTA_EXHAUSTED, "credit balance is too low")
        val models = ScriptedModelGateway(ScriptedModelGateway.Reply.Failure(noQuota), ScriptedModelGateway.answer("second try"))
        val approver = ScriptedApprover({ ApprovalDecision.Continue })

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "go"), TurnCallbacks(approver = approver))

        val request = approver.requests.single()
        assertTrue(request.obstacles.single() is RoutingObstacle.Unavailable)
        assertTrue(request.alternatives.none { it.provider.id == ProviderId("anthropic") }, request.alternatives.toString())
        assertEquals(2, models.requests.size)
        assertEquals("second try", summary.finalText)
        assertTrue(rateLimits.cooldowns.isEmpty())
    }
}
