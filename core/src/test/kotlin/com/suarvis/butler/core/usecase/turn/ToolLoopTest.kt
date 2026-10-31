package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.StopReason
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.permission.ToolPattern
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The model → tool → model loop (A2), its permission step (A5) and cancellation (A6). The rule for
 * every test: the saved session has no pending tool calls.
 */
class ToolLoopTest {
    private val sessions = InMemorySessionGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val workspace = SessionFixtures.workspace
    private val usage = InMemoryUsageGateway()
    private val rateLimits = InMemoryRateLimitGateway()
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
    ) = RunTurnUseCase(
        GetSessionUseCase(sessions),
        sessions,
        SequentialIdentityGateway(),
        models,
        FixedModelCatalog(),
        usage,
        rateLimits,
        FixedBudgetGateway(),
        clock,
        RunToolCallUseCase(inputs, files, commands, mcp),
        mcp,
        rules,
        TurnSettings(toolsEnabled = toolsEnabled),
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
}
