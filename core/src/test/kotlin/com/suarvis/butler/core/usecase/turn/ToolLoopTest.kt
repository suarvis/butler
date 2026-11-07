package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.budget.Budget
import com.suarvis.butler.core.domain.budget.BudgetScope
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.exception.BudgetExceededException
import com.suarvis.butler.core.domain.exception.IterationLimitException
import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.permission.ToolPattern
import com.suarvis.butler.core.domain.routing.RoutingObstacle
import com.suarvis.butler.core.domain.routing.TokenEstimator
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.ApprovalDecision
import com.suarvis.butler.core.domain.turn.ApprovalRequest
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.TurnCallbacks
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.domain.turn.TurnSettings
import com.suarvis.butler.core.domain.turn.TurnSummary
import com.suarvis.butler.core.fixtures.CancelAfter
import com.suarvis.butler.core.fixtures.FakeMcpGateway
import com.suarvis.butler.core.fixtures.FakeToolInputGateway
import com.suarvis.butler.core.fixtures.FixedBudgetGateway
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import com.suarvis.butler.core.fixtures.FixedPermissionRulesGateway
import com.suarvis.butler.core.fixtures.InMemoryRateLimitGateway
import com.suarvis.butler.core.fixtures.InMemorySessionGateway
import com.suarvis.butler.core.fixtures.InMemoryUsageGateway
import com.suarvis.butler.core.fixtures.MutableClock
import com.suarvis.butler.core.fixtures.RecordingCommandGateway
import com.suarvis.butler.core.fixtures.RecordingToolApprover
import com.suarvis.butler.core.fixtures.RecordingTurnApprover
import com.suarvis.butler.core.fixtures.RecordingWorkspaceGateway
import com.suarvis.butler.core.fixtures.ScriptedModelGateway
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.answer
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.call
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.toolCall
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.toolCalls
import com.suarvis.butler.core.fixtures.SequentialIdentityGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.gateway.PermissionRulesGateway
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * The model → tool → model loop (A2), its permission step (A5), cancellation (A6), the iteration
 * limit (A3) and budgets and usage per model call (A4). The rule for every test: the saved session
 * has no pending tool calls.
 */
class ToolLoopTest {
    private val sessions = InMemorySessionGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val workspace = SessionFixtures.workspace
    private val usage = InMemoryUsageGateway()
    private val rateLimits = InMemoryRateLimitGateway()
    private val budgets = FixedBudgetGateway()
    private val readme = WorkspacePath("README.md")
    private val search =
        ToolDefinition(ToolName("mcp__docs__search"), "Search the docs.", JsonText("""{"type":"object"}"""), PermissionTier.READ_ONLY)

    private val files = RecordingWorkspaceGateway(ToolOutcome("# Butler"), ToolOutcome("src/Main.kt"))
    private val commands = RecordingCommandGateway(ToolOutcome("README.md"))
    private val mcp = FakeMcpGateway(tools = listOf(search), outcomes = mapOf(search.name to ToolOutcome("3 hits")))

    /** Each tool decodes to one fixed invocation, so the tests state what runs instead of parsing JSON. */
    private val inputs =
        FakeToolInputGateway { name, input ->
            when (name.value) {
                "read_file" -> ToolInvocation.ReadFile(readme)
                "glob_search" -> ToolInvocation.GlobSearch("**/*.kt")
                "write_file" -> ToolInvocation.WriteFile(readme, "# Demo")
                "bash" -> ToolInvocation.Bash("ls")
                else -> ToolInvocation.External(name, input)
            }
        }

    private fun useCase(
        models: ScriptedModelGateway,
        toolsEnabled: Boolean = true,
        rules: PermissionRulesGateway = FixedPermissionRulesGateway(),
        maxIterations: Int = TurnSettings.DEFAULT_MAX_ITERATIONS,
    ) = RunTurnUseCase(
        GetSessionUseCase(sessions),
        sessions,
        SequentialIdentityGateway(),
        models,
        FixedModelCatalog(),
        usage,
        rateLimits,
        budgets,
        clock,
        RunToolCallUseCase(inputs, files, commands, mcp),
        mcp,
        rules,
        TurnSettings(toolsEnabled = toolsEnabled, maxIterations = maxIterations),
    )

    private fun saved(summary: TurnSummary): Session {
        val saved = sessions.findById(workspace, summary.session.id)!!
        assertEquals(summary.session, saved)
        assertTrue(saved.pendingToolCalls().isEmpty(), "pending: ${saved.pendingToolCalls()}")
        return saved
    }

    private fun Session.results(): List<ContentBlock.ToolResult> = messages.flatMap { it.toolResults }

    /** A following prompt continues the saved session, so the transcript the turn left is valid. */
    private fun assertContinues(summary: TurnSummary, models: ScriptedModelGateway, answer: String) {
        val next = useCase(models).execute(RunTurnUseCase.Command(workspace, "Go on", session = SessionReference.ById(summary.session.id)))

        assertEquals(answer, next.finalText)
        assertEquals(summary.session.messages, saved(next).messages.take(summary.session.messages.size))
    }

    /** The same for a turn that failed: a following prompt continues the session it saved. */
    private fun assertLatestContinues(turns: RunTurnUseCase, answer: String): TurnSummary {
        val stopped = sessions.findLatest(workspace)!!
        assertTrue(stopped.pendingToolCalls().isEmpty(), "pending: ${stopped.pendingToolCalls()}")

        val next = turns.execute(RunTurnUseCase.Command(workspace, "Go on", session = SessionReference.ById(stopped.id)))

        assertEquals(answer, next.finalText)
        assertEquals(stopped.messages, saved(next).messages.take(stopped.messages.size))
        return next
    }

    private fun anthropicBudget(usd: String) = Budget(BudgetScope.Provider(ProviderId("anthropic")), BigDecimal(usd), null)

    /** The estimated input of the budget check that raised [request], read back from its breach. */
    private fun estimatedInput(request: ApprovalRequest): Long {
        val breach = (request.obstacles.single() as RoutingObstacle.OverBudget).breaches.single()
        return breach.projected.tokens - breach.spent.tokens
    }

    private fun recording(events: MutableList<TurnEvent>, cancellation: CancelAfter, approver: RecordingToolApprover = RecordingToolApprover()) =
        TurnCallbacks(observer = TurnObserver { events += it }, toolApprover = approver, cancellation = cancellation)

    private fun List<TurnEvent>.names(): List<String> = filterNot { it is TurnEvent.TextDelta }.map { it.javaClass.simpleName }

    @Test
    fun `a read_file call and then an answer ends the turn with the answer`() {
        val models = ScriptedModelGateway(toolCall("read_file", """{"path":"README.md"}"""), answer("It is Butler's readme."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "What is in the readme?"))

        assertEquals("It is Butler's readme.", summary.finalText)
        assertEquals(StopReason.END_TURN, summary.stopReason)
        assertEquals(2, summary.iterations)
        assertEquals(2, summary.assistantMessages.size)
        assertEquals(TokenUsage(inputTokens = 20, outputTokens = 10), summary.usage)
        assertEquals(2, usage.records.size)
        assertEquals(listOf<ToolInvocation>(ToolInvocation.ReadFile(readme)), files.invocations)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT),
            saved(summary).messages.map { it.role },
        )
    }

    @Test
    fun `every request offers the tools and the second one carries the call and its result`() {
        val models = ScriptedModelGateway(toolCall("read_file", """{"path":"README.md"}"""), answer("ok"))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "Read the readme"))

        assertEquals(2, models.requests.size)
        models.requests.forEach { assertEquals(BuiltInTools.ALL + search, it.tools) }
        val second = models.requests[1].messages
        assertEquals(listOf(call("toolu_read_file", "read_file", """{"path":"README.md"}""")), second[1].toolUses)
        assertEquals(MessageRole.TOOL, second.last().role)
        assertEquals(
            listOf(ContentBlock.ToolResult(ToolCallId("toolu_read_file"), ToolName("read_file"), "# Butler", isError = false)),
            second.last().toolResults,
        )
    }

    @Test
    fun `two calls in one reply run in the order the model made them`() {
        val models =
            ScriptedModelGateway(
                toolCalls(call("toolu_1", "read_file"), call("toolu_2", "glob_search"), text = "Let me look."),
                answer("Done."),
            )

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"))

        assertEquals(listOf(ToolInvocation.ReadFile(readme), ToolInvocation.GlobSearch("**/*.kt")), files.invocations)
        val saved = saved(summary)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL, MessageRole.ASSISTANT),
            saved.messages.map { it.role },
        )
        assertEquals(listOf(ToolCallId("toolu_1"), ToolCallId("toolu_2")), saved.results().map { it.toolUseId })
        assertEquals(listOf("# Butler", "src/Main.kt"), saved.results().map { it.output })
        assertEquals(2, summary.iterations)
    }

    @Test
    fun `events arrive as started, tool started, tool finished, completed`() {
        val events = mutableListOf<TurnEvent>()
        val models = ScriptedModelGateway(toolCall("read_file"), answer("ok"))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(observer = TurnObserver { events += it }))

        val steps = events.filterNot { it is TurnEvent.TextDelta }
        assertEquals(listOf("Started", "ToolStarted", "ToolFinished", "Completed"), steps.map { it.javaClass.simpleName })
        val started = steps[1] as TurnEvent.ToolStarted
        val finished = steps[2] as TurnEvent.ToolFinished
        assertEquals(call("toolu_read_file", "read_file"), started.call)
        assertEquals(started.call, finished.call)
        assertEquals("# Butler", finished.result.output)
    }

    @Test
    fun `MCP tools are offered after the built-ins and their calls reach the MCP gateway`() {
        val models = ScriptedModelGateway(toolCall("mcp__docs__search", """{"query":"loop"}"""), answer("Found it."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Search the docs"))

        assertEquals(BuiltInTools.ALL + search, models.requests.first().tools)
        assertEquals(listOf(FakeMcpGateway.Call(search.name, JsonText("""{"query":"loop"}"""))), mcp.calls)
        assertEquals("3 hits", saved(summary).results().single().output)
    }

    @Test
    fun `allowedTools narrows the offer and a call to any other tool gets an error result`() {
        val models = ScriptedModelGateway(toolCall("bash", """{"command":"ls"}"""), answer("I may not run commands."))
        val allowed = setOf(ToolName("read_file"), ToolName("glob_search"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "List files", allowedTools = allowed))

        models.requests.forEach { assertEquals(listOf(ToolName("read_file"), ToolName("glob_search")), it.tools.map { tool -> tool.name }) }
        assertTrue(commands.commands.isEmpty())
        val result = saved(summary).results().single()
        assertEquals("unsupported tool: bash", result.output)
        assertTrue(result.isError)
        assertEquals("I may not run commands.", summary.finalText)
    }

    @Test
    fun `with tools off a turn offers nothing, runs nothing and answers a stray call as before`() {
        val models = ScriptedModelGateway(toolCalls(call("toolu_1", "read_file"), text = "Reading."))

        val summary = useCase(models, toolsEnabled = false).execute(RunTurnUseCase.Command(workspace, "Read the readme"))

        assertTrue(models.requests.single().tools.isEmpty())
        assertEquals(1, summary.iterations)
        assertEquals(1, usage.records.size)
        assertTrue(files.invocations.isEmpty())
        assertTrue(mcp.calls.isEmpty())
        val result = saved(summary).results().single()
        assertEquals(ToolCallId("toolu_1"), result.toolUseId)
        assertEquals("Tools are not available in this version of Butler", result.output)
        assertTrue(result.isError)
        assertEquals("Reading.", summary.finalText)
    }

    // The permission step (A5)

    @Test
    fun `a call the person denies reaches the model as an error result`() {
        val approver = RecordingToolApprover(ToolApprovalDecision.DENY)
        val models = ScriptedModelGateway(toolCall("write_file", """{"path":"README.md","content":"# Demo"}"""), answer("I was not allowed to write."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Write the readme"), TurnCallbacks(toolApprover = approver))

        val request = approver.requests.single()
        assertEquals(PermissionMode.PROMPT, request.mode)
        assertEquals(PermissionTier.WORKSPACE_WRITE, request.tier)
        assertTrue(files.invocations.isEmpty(), "nothing is written")
        val denied = ContentBlock.ToolResult(ToolCallId("toolu_write_file"), ToolName("write_file"), "tool 'write_file' denied by user approval prompt", isError = true)
        assertEquals(listOf(denied), models.requests[1].messages.last().toolResults)
        assertEquals(listOf(denied), saved(summary).results())
        assertEquals("I was not allowed to write.", summary.finalText)
    }

    @Test
    fun `a deny rule refuses the call without asking anyone`() {
        val approver = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)
        val rules = FixedPermissionRulesGateway(PermissionRules(deny = listOf(ToolPattern(ToolName("read_file")))))
        val models = ScriptedModelGateway(toolCall("read_file"), answer("Reading is denied."))

        val summary = useCase(models, rules = rules).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(toolApprover = approver))

        assertTrue(approver.requests.isEmpty())
        assertTrue(files.invocations.isEmpty())
        val result = saved(summary).results().single()
        assertEquals("Permission to use read_file has been denied by rule 'read_file'", result.output)
        assertTrue(result.isError)
    }

    @Test
    fun `the rules are read once per turn and the command's mode decides each call`() {
        var reads = 0
        val rules =
            object : PermissionRulesGateway {
                override fun rules(): PermissionRules {
                    reads++
                    return PermissionRules.NONE
                }
            }
        val models = ScriptedModelGateway(toolCalls(call("toolu_1", "read_file"), call("toolu_2", "write_file")), answer("Read, not written."))

        val summary =
            useCase(models, rules = rules).execute(RunTurnUseCase.Command(workspace, "Read then write", permissionMode = PermissionMode.READ_ONLY))

        assertEquals(1, reads)
        assertEquals(
            listOf("# Butler", "tool 'write_file' requires workspace-write permission; current mode is read-only"),
            saved(summary).results().map { it.output },
        )
        assertEquals(listOf<ToolInvocation>(ToolInvocation.ReadFile(readme)), files.invocations)
    }

    // Cancellation (A6): polls come before each model call, on each streamed event, before each tool call and after each approval

    @Test
    fun `a turn cancelled before its first model call saves the prompt and calls nothing`() {
        val events = mutableListOf<TurnEvent>()
        val models = ScriptedModelGateway(answer("Picked up again."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), recording(events, CancelAfter(0)))

        assertTrue(summary.interrupted)
        assertTrue(models.requests.isEmpty())
        assertEquals(0, summary.iterations)
        assertTrue(summary.assistantMessages.isEmpty())
        assertTrue(usage.records.isEmpty())
        assertEquals(listOf(MessageRole.USER), saved(summary).messages.map { it.role })
        assertEquals(listOf("Started", "Completed"), events.names())
        assertEquals(summary, (events.last() as TurnEvent.Completed).summary)
        assertContinues(summary, models, "Picked up again.")
    }

    @Test
    fun `a turn cancelled before a tool call answers that call and every later one as interrupted`() {
        val events = mutableListOf<TurnEvent>()
        val models = ScriptedModelGateway(toolCalls(call("toolu_1", "read_file"), call("toolu_2", "glob_search")), answer("Picked up again."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"), recording(events, CancelAfter(1)))

        assertTrue(summary.interrupted)
        assertEquals(1, models.requests.size)
        assertEquals(1, summary.iterations)
        assertEquals(StopReason.TOOL_USE, summary.stopReason)
        assertTrue(files.invocations.isEmpty(), "nothing runs")
        val results = saved(summary).results()
        assertEquals(listOf(ToolCallId("toolu_1"), ToolCallId("toolu_2")), results.map { it.toolUseId })
        results.forEach {
            assertEquals("Interrupted by user before this tool could run.", it.output)
            assertTrue(it.isError)
        }
        assertEquals(listOf("Started", "ToolStarted", "ToolFinished", "ToolStarted", "ToolFinished", "Completed"), events.names())
        assertContinues(summary, models, "Picked up again.")
    }

    @Test
    fun `a turn cancelled between two calls keeps the first result and interrupts the second`() {
        val models = ScriptedModelGateway(toolCalls(call("toolu_1", "read_file"), call("toolu_2", "glob_search")))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"), recording(mutableListOf(), CancelAfter(2)))

        assertTrue(summary.interrupted)
        assertEquals(listOf<ToolInvocation>(ToolInvocation.ReadFile(readme)), files.invocations)
        assertEquals(listOf("# Butler", "Interrupted by user before this tool could run."), saved(summary).results().map { it.output })
    }

    @Test
    fun `a turn cancelled after its tool results makes no further model call`() {
        val models = ScriptedModelGateway(toolCall("read_file"), answer("Picked up again."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), recording(mutableListOf(), CancelAfter(2)))

        assertTrue(summary.interrupted)
        assertEquals(1, models.requests.size)
        assertEquals(1, summary.iterations)
        assertEquals("# Butler", saved(summary).results().single().output)
        assertContinues(summary, models, "Picked up again.")
    }

    @Test
    fun `a turn cancelled during an approval prompt runs nothing and stops`() {
        val approver = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)
        val models = ScriptedModelGateway(toolCall("write_file"), answer("Picked up again."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Write"), recording(mutableListOf(), CancelAfter(2), approver))

        assertTrue(summary.interrupted)
        assertEquals(1, approver.requests.size)
        assertTrue(files.invocations.isEmpty(), "nothing is written")
        assertEquals(1, models.requests.size)
        assertEquals("Interrupted by user before this tool could run.", saved(summary).results().single().output)
        assertContinues(summary, models, "Picked up again.")
    }

    @Test
    fun `a turn cancelled while the reply streams keeps none of it`() {
        val events = mutableListOf<TurnEvent>()
        val models = ScriptedModelGateway(answer("Hel", "lo"), answer("Picked up again."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Hi"), recording(events, CancelAfter(2)))

        assertTrue(summary.interrupted)
        assertEquals(1, summary.iterations)
        assertTrue(summary.assistantMessages.isEmpty())
        assertTrue(usage.records.isEmpty())
        assertEquals(listOf(MessageRole.USER), saved(summary).messages.map { it.role })
        assertEquals(listOf(TurnEvent.TextDelta("Hel")), events.filterIsInstance<TurnEvent.TextDelta>())
        assertEquals(listOf("Started", "Completed"), events.names())
        assertContinues(summary, models, "Picked up again.")
    }

    @Test
    fun `a provider failure after a cancel is an interruption, not a failure to retry`() {
        val failure = ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.RATE_LIMITED, "429", retryAfterSeconds = 30)
        val models = ScriptedModelGateway(ScriptedModelGateway.Reply.Failure(failure))

        // The default turn approver stops, which would rethrow the failure if it were asked.
        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Hi"), recording(mutableListOf(), CancelAfter(1)))

        assertTrue(summary.interrupted)
        assertTrue(rateLimits.cooldowns.isEmpty(), "no cooldown is remembered")
        assertEquals(listOf(MessageRole.USER), saved(summary).messages.map { it.role })
    }

    // The iteration limit (A3)

    @Test
    fun `a model that keeps calling tools stops at the limit with every call answered and the session saved`() {
        val models =
            ScriptedModelGateway(
                toolCall("mcp__docs__search", id = "toolu_1"),
                toolCall("mcp__docs__search", id = "toolu_2"),
                toolCall("mcp__docs__search", id = "toolu_3"),
                answer("Never asked for."),
            )

        val error = assertThrows<IterationLimitException> { useCase(models, maxIterations = 3).execute(RunTurnUseCase.Command(workspace, "Search until done")) }

        assertEquals(3, error.limit)
        assertEquals("conversation loop exceeded the maximum number of iterations (3)", error.message)
        assertEquals(3, models.requests.size)
        assertEquals(3, mcp.calls.size)
        assertEquals(3, usage.records.size)
        val saved = sessions.findLatest(workspace)!!
        assertTrue(saved.pendingToolCalls().isEmpty(), "pending: ${saved.pendingToolCalls()}")
        assertEquals(
            listOf(MessageRole.USER) + List(3) { listOf(MessageRole.ASSISTANT, MessageRole.TOOL) }.flatten(),
            saved.messages.map { it.role },
        )
        assertEquals(listOf(ToolCallId("toolu_1"), ToolCallId("toolu_2"), ToolCallId("toolu_3")), saved.results().map { it.toolUseId })
    }

    @Test
    fun `the limit counts model calls, so an answer on the last allowed call ends the turn normally`() {
        val models = ScriptedModelGateway(toolCall("read_file"), answer("Read it."))

        val summary = useCase(models, maxIterations = 2).execute(RunTurnUseCase.Command(workspace, "Read"))

        assertEquals("Read it.", summary.finalText)
        assertEquals(2, summary.iterations)
        assertFalse(summary.interrupted)
    }

    @Test
    fun `a following prompt continues a turn stopped at the limit, and counts its own calls afresh`() {
        val models =
            ScriptedModelGateway(
                toolCall("read_file", id = "toolu_1"),
                toolCall("glob_search", id = "toolu_2"),
                toolCall("mcp__docs__search", id = "toolu_3"),
                answer("Found it."),
            )
        val turns = useCase(models, maxIterations = 2)
        assertThrows<IterationLimitException> { turns.execute(RunTurnUseCase.Command(workspace, "Look around")) }

        val next = assertLatestContinues(turns, "Found it.")

        assertEquals(2, next.iterations)
        val results = saved(next).results()
        assertEquals(listOf(ToolCallId("toolu_1"), ToolCallId("toolu_2"), ToolCallId("toolu_3")), results.map { it.toolUseId })
        assertEquals(listOf("# Butler", "src/Main.kt", "3 hits"), results.map { it.output })
    }

    @Test
    fun `a cancel at the limit is an interruption, because Clawcode polls for one first`() {
        val models = ScriptedModelGateway(toolCall("read_file"))

        // Polls: before the first call, before the tool call, then before the second call, which the limit would refuse.
        val summary = useCase(models, maxIterations = 1).execute(RunTurnUseCase.Command(workspace, "Read"), recording(mutableListOf(), CancelAfter(2)))

        assertTrue(summary.interrupted)
        assertEquals(1, summary.iterations)
        assertEquals("# Butler", saved(summary).results().single().output)
    }

    // Budgets and usage per model call (A4)

    @Test
    fun `a budget the first call used up reaches the approver before the second call`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        val approver = RecordingTurnApprover({ ApprovalDecision.Continue })
        // 400k input tokens at the test price of $3 per million and 5 output tokens at $15: $1.200075.
        val models = ScriptedModelGateway(toolCall("read_file", usage = TokenUsage(inputTokens = 400_000, outputTokens = 5)), answer("Read it."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(approver = approver))

        val request = approver.requests.single()
        assertEquals(ModelId("claude-sonnet-4-6"), request.model.id)
        val breach = (request.obstacles.single() as RoutingObstacle.OverBudget).breaches.single()
        assertEquals(0, BigDecimal("1.200075").compareTo(breach.spent.usd), breach.spent.toString())
        assertEquals(2, models.requests.size)
        assertEquals("Read it.", summary.finalText)
    }

    @Test
    fun `the budget check before each call estimates the transcript that call sends, tool results included`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        usage.record(UsageRecord(SessionFixtures.start, ProviderId("anthropic"), ModelId("claude-sonnet-4-6"), TokenUsage(inputTokens = 1000), BigDecimal("2.00")))
        val approver = RecordingTurnApprover({ ApprovalDecision.Continue }, { ApprovalDecision.Continue })
        val models = ScriptedModelGateway(toolCall("read_file"), answer("Read it."))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(approver = approver))

        val estimates = approver.requests.map { estimatedInput(it) }
        assertEquals(models.requests.map { TokenEstimator.estimate(it.systemPrompt, it.messages) }, estimates)
        assertTrue(estimates[1] > estimates[0], estimates.toString())
    }

    @Test
    fun `stopping at a budget before the second call fails the turn after the tool results are saved`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        val approver = RecordingTurnApprover({ ApprovalDecision.Stop })
        val expensive = TokenUsage(inputTokens = 400_000, outputTokens = 5)
        val models =
            ScriptedModelGateway(toolCalls(call("toolu_1", "read_file"), call("toolu_2", "glob_search"), usage = expensive), answer("Picked up again."))

        val error =
            assertThrows<BudgetExceededException> {
                useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"), TurnCallbacks(approver = approver))
            }

        assertTrue(error.message!!.contains("anthropic monthly budget: $1.20 of $1.00 spent"), error.message)
        assertEquals(1, approver.requests.size)
        assertEquals(1, models.requests.size)
        assertEquals(1, usage.records.size)
        val saved = sessions.findLatest(workspace)!!
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL), saved.messages.map { it.role })
        assertEquals(listOf("# Butler", "src/Main.kt"), saved.results().map { it.output })
        budgets.budgets = emptyList()
        assertLatestContinues(useCase(models), "Picked up again.")
    }

    @Test
    fun `switching model before the second call moves the rest of the turn to it`() {
        budgets.budgets = listOf(anthropicBudget("1.00"))
        val approver = RecordingTurnApprover({ request -> ApprovalDecision.SwitchTo(request.alternatives.single()) })
        val models =
            ScriptedModelGateway(
                toolCall("read_file", id = "toolu_1", usage = TokenUsage(inputTokens = 400_000, outputTokens = 5)),
                toolCall("glob_search", id = "toolu_2"),
                answer("From GPT."),
            )

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"), TurnCallbacks(approver = approver))

        assertEquals(1, approver.requests.size)
        assertEquals(listOf("claude-sonnet-4-6", "gpt-5.5", "gpt-5.5"), models.requests.map { it.model.id.value })
        assertEquals(listOf("claude-sonnet-4-6", "gpt-5.5", "gpt-5.5"), usage.records.map { it.model.value })
        assertEquals(ModelId("gpt-5.5"), summary.model.id)
        assertEquals(ModelId("gpt-5.5"), saved(summary).model)
        assertEquals("From GPT.", summary.finalText)
    }

    @Test
    fun `the ledger gets one record per model call, with that call's usage and cost`() {
        val models =
            ScriptedModelGateway(
                toolCall("read_file", id = "toolu_1", usage = TokenUsage(inputTokens = 100, outputTokens = 20)),
                toolCall("glob_search", id = "toolu_2", usage = TokenUsage(inputTokens = 200, outputTokens = 30)),
                answer("Done.", usage = TokenUsage(inputTokens = 300, outputTokens = 40)),
            )

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Look around"))

        assertEquals(
            listOf(TokenUsage(inputTokens = 100, outputTokens = 20), TokenUsage(inputTokens = 200, outputTokens = 30), TokenUsage(inputTokens = 300, outputTokens = 40)),
            usage.records.map { it.usage },
        )
        // At the test price of $3 per million input tokens and $15 per million output tokens.
        assertEquals(listOf("0.0006", "0.00105", "0.0015"), usage.records.map { it.costUsd!!.stripTrailingZeros().toPlainString() })
        usage.records.forEach { assertEquals(ProviderId("anthropic"), it.provider) }
        assertEquals(TokenUsage(inputTokens = 600, outputTokens = 90), summary.usage)
        assertEquals(3, summary.iterations)
    }

    @Test
    fun `a 429 on the second call sets a cooldown and offers the other providers`() {
        val limited = ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.RATE_LIMITED, "slow down", retryAfterSeconds = 20)
        val approver = RecordingTurnApprover({ request -> ApprovalDecision.SwitchTo(request.alternatives.first()) })
        val models = ScriptedModelGateway(toolCall("read_file"), ScriptedModelGateway.Reply.Failure(limited), answer("From GPT."))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(approver = approver))

        assertEquals(SessionFixtures.start.plusSeconds(20), rateLimits.cooldowns[ProviderId("anthropic")])
        val request = approver.requests.single()
        assertTrue(request.obstacles.single() is RoutingObstacle.RateLimited, request.obstacles.toString())
        assertEquals(listOf(ModelId("gpt-5.5")), request.alternatives.map { it.id })
        assertEquals(listOf("claude-sonnet-4-6", "claude-sonnet-4-6", "gpt-5.5"), models.requests.map { it.model.id.value })
        // The other provider gets what the failed call sent, the tool result included.
        assertEquals(models.requests[1].messages, models.requests[2].messages)
        assertEquals("# Butler", models.requests[2].messages.last().toolResults.single().output)
        assertEquals(2, usage.records.size, "a failed call is not recorded")
        assertEquals(ModelId("gpt-5.5"), saved(summary).model)
        assertEquals("From GPT.", summary.finalText)
    }

    @Test
    fun `retries are counted per model call, so earlier ones never cut a long turn short`() {
        val overloaded =
            ScriptedModelGateway.Reply.Failure(ModelProviderException(ProviderId("anthropic"), ModelProviderException.Failure.OVERLOADED, "overloaded"))
        // Four failures and then a success take all five attempts of a call, twice in one turn.
        val models =
            ScriptedModelGateway(overloaded, overloaded, overloaded, overloaded, toolCall("read_file"), overloaded, overloaded, overloaded, overloaded, answer("Finally."))
        val retry: (ApprovalRequest) -> ApprovalDecision = { ApprovalDecision.Continue }
        val approver = RecordingTurnApprover(*Array(8) { retry })

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Read"), TurnCallbacks(approver = approver))

        assertEquals("Finally.", summary.finalText)
        assertEquals(10, models.requests.size)
        assertEquals(8, approver.requests.size)
        assertEquals(2, summary.iterations)
        assertEquals(2, usage.records.size)
    }
}
