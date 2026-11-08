package com.suarvis.butler.api.cli.repl

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.CliEnvironment
import com.suarvis.butler.api.cli.ExitCodes
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.action.PromptActions
import com.suarvis.butler.api.cli.action.SessionActions
import com.suarvis.butler.api.cli.action.UsageActions
import com.suarvis.butler.api.cli.command.CliInvocation
import com.suarvis.butler.api.cli.exception.CliExceptionHandler
import com.suarvis.butler.api.cli.exception.CliInterruptedException
import com.suarvis.butler.api.cli.prompt.CliToolApprovers
import com.suarvis.butler.api.cli.prompt.CliTurnApprovers
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.TerminalStyle
import com.suarvis.butler.api.config.CliProperties
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnSettings
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
import com.suarvis.butler.core.fixtures.ScriptedModelGateway
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.answer
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.call
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.toolCall
import com.suarvis.butler.core.fixtures.ScriptedModelGateway.Companion.toolCalls
import com.suarvis.butler.core.fixtures.SequentialIdentityGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.gateway.WorkspaceGateway
import com.suarvis.butler.core.usecase.budget.GetUsageReportUseCase
import com.suarvis.butler.core.usecase.model.ResolveModelUseCase
import com.suarvis.butler.core.usecase.session.DeleteSessionUseCase
import com.suarvis.butler.core.usecase.session.ForkSessionUseCase
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import com.suarvis.butler.core.usecase.session.ListSessionsUseCase
import com.suarvis.butler.core.usecase.turn.RunToolCallUseCase
import com.suarvis.butler.core.usecase.turn.RunTurnUseCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import kotlin.concurrent.thread

/**
 * Ctrl-C during a turn, through the real REPL, prompt actions and turn loop, with core's fakes for
 * the model, the sessions and the tools. The rule for every test: the interrupted turn leaves a valid
 * transcript, so the next prompt continues the same session.
 */
class InterruptedTurnTest {
    private val sessions = InMemorySessionGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val identity = SequentialIdentityGateway()
    private val catalog = FixedModelCatalog()
    private val usage = InMemoryUsageGateway()
    private val budgets = FixedBudgetGateway()
    private val mcp = FakeMcpGateway()
    private val signals = ManualInterruptSignals()
    private val exits = mutableListOf<Int>()
    private val workspace: WorkspaceRoot = SessionFixtures.workspace
    private val readme = WorkspacePath("README.md")
    private val files = SlowFiles()

    /** Each tool decodes to one fixed invocation, so the tests state what runs instead of parsing JSON. */
    private val inputs =
        FakeToolInputGateway { name, input ->
            when (name.value) {
                "read_file" -> ToolInvocation.ReadFile(readme)
                "glob_search" -> ToolInvocation.GlobSearch("**/*.kt")
                "write_file" -> ToolInvocation.WriteFile(readme, "# Demo")
                else -> ToolInvocation.External(name, input)
            }
        }

    @Test
    fun `Ctrl-C during a tool call stops the turn, and the next prompt runs on a valid transcript`() {
        val models = ScriptedModelGateway(toolCalls(call("toolu_read", "read_file"), call("toolu_glob", "glob_search")), answer("The readme is a heading."))
        files.pressCtrlCWhileRunning = true
        val console = ScriptedConsole(interactive = false, "Read the readme, then find the Kotlin files", "Go on")

        val exitCode = repl(console, models).run(CliInvocation(OutputFormat.TEXT, workspace))

        assertEquals(ExitCodes.OK, exitCode)
        assertEquals(emptyList<Int>(), exits)
        assertTrue(console.errors.contains(TurnInterrupts.NOTICE), console.errors)
        assertTrue(console.printed.contains("\nInterrupted\n"), console.printed)
        assertTrue(console.printed.contains("The readme is a heading."), console.printed)
        // The call that was running finished; the one after it never ran.
        assertEquals(listOf<ToolInvocation>(ToolInvocation.ReadFile(readme)), files.invocations)
        // No model call after the interrupt: the second request is the next prompt's.
        assertEquals(2, models.requests.size)
        val next = models.requests[1]
        assertValidTranscript(next)
        assertEquals("# Demo", results(next).getValue(ToolCallId("toolu_read")).output)
        val skipped = results(next).getValue(ToolCallId("toolu_glob"))
        assertEquals(INTERRUPTED_RESULT, skipped.output)
        assertTrue(skipped.isError)
        assertEquals(MessageRole.USER, next.messages.last().role)
        assertEquals("Go on", next.messages.last().text)
        assertTrue(sessions.saved.single().pendingToolCalls().isEmpty())
    }

    @Test
    fun `Ctrl-C at an approval prompt denies the call and stops the turn, and the REPL goes on`() {
        val models = ScriptedModelGateway(toolCall("write_file", """{"path":"README.md","content":"# Demo"}"""), answer("Left the readme alone."))
        val console = ScriptedConsole(interactive = true, "Write a demo readme", CTRL_C, "Go on")

        repl(console, models).run(CliInvocation(OutputFormat.TEXT, workspace))

        assertEquals(emptyList<Int>(), exits)
        assertTrue(console.printed.contains("Approve this tool call? [y/a/N]: "), console.printed)
        assertTrue(console.errors.contains(TurnInterrupts.NOTICE), console.errors)
        assertTrue(console.printed.contains("\nInterrupted\n"), console.printed)
        assertTrue(files.invocations.isEmpty(), "the write never ran")
        assertEquals(2, models.requests.size)
        val next = models.requests[1]
        assertValidTranscript(next)
        assertEquals(INTERRUPTED_RESULT, results(next).getValue(ToolCallId("toolu_write_file")).output)
        assertEquals("Go on", next.messages.last().text)
        assertTrue(console.printed.contains("Left the readme alone."), console.printed)
    }

    @Test
    fun `a one-shot prompt that Ctrl-C stopped ends in the interrupted error, exit code 130, and no result`() {
        val models = ScriptedModelGateway(toolCalls(call("toolu_read", "read_file"), call("toolu_glob", "glob_search")))
        files.pressCtrlCWhileRunning = true
        val console = ScriptedConsole(interactive = false)
        val invocation = CliInvocation(OutputFormat.JSON, workspace)

        val error = assertThrows<CliInterruptedException> { prompts(console, models).runOnce(invocation, "Read the readme", session = null, compact = false) }
        val exitCode = CliExceptionHandler(CliOutputWriter(console), CliEnvironment()).report(error, OutputFormat.JSON)

        assertEquals(ExitCodes.INTERRUPTED, exitCode)
        // The error envelope is the only output: the interrupted turn's result is not printed.
        val envelope = Json.parseToJsonElement(console.printed.trim()).jsonObject
        assertEquals("interrupted", envelope["kind"]!!.jsonPrimitive.content)
        assertEquals(ExitCodes.INTERRUPTED.toString(), envelope["exit_code"]!!.jsonPrimitive.content)
        assertTrue(envelope["hint"]!!.jsonPrimitive.content.contains("butler --resume ${sessions.saved.single().id.value}"))
        assertTrue(sessions.saved.single().pendingToolCalls().isEmpty())
    }

    private fun prompts(console: CliConsole, models: ScriptedModelGateway): PromptActions {
        val runTurn =
            RunTurnUseCase(
                GetSessionUseCase(sessions),
                sessions,
                identity,
                models,
                catalog,
                usage,
                InMemoryRateLimitGateway(),
                budgets,
                clock,
                RunToolCallUseCase(inputs, files, RecordingCommandGateway(), mcp),
                mcp,
                FixedPermissionRulesGateway(),
                TurnSettings(toolsEnabled = true),
            )
        return PromptActions(
            runTurn = runTurn,
            output = CliOutputWriter(console),
            console = console,
            approvers = CliTurnApprovers(console, clock),
            toolApprovers = CliToolApprovers(console),
            interrupts = interrupts(console),
            cli = CliProperties(color = TerminalStyle.ColorMode.NEVER),
            environment = CliEnvironment(),
        )
    }

    private fun repl(console: CliConsole, models: ScriptedModelGateway): ReplRunner {
        val output = CliOutputWriter(console)
        val getSession = GetSessionUseCase(sessions)
        return ReplRunner(
            prompts = prompts(console, models),
            sessions = SessionActions(getSession, ListSessionsUseCase(sessions), ForkSessionUseCase(getSession, sessions, identity, clock), DeleteSessionUseCase(getSession, sessions), output, "file"),
            usage = UsageActions(GetUsageReportUseCase(usage, budgets, clock), output),
            resolveModel = ResolveModelUseCase(catalog),
            approvers = CliTurnApprovers(console, clock),
            toolApprovers = CliToolApprovers(console),
            exceptions = CliExceptionHandler(output, CliEnvironment()),
            console = console,
            interrupts = interrupts(console),
        )
    }

    /** A second Ctrl-C would end the process; here it is only recorded. */
    private fun interrupts(console: CliConsole) = TurnInterrupts(console, signals) { code -> exits += code }

    /** Every tool call has exactly one result, in order: what every provider requires of a transcript. */
    private fun assertValidTranscript(request: ModelRequest) {
        val blocks = request.messages.flatMap { it.blocks }
        assertEquals(blocks.filterIsInstance<ContentBlock.ToolUse>().map { it.id }, blocks.filterIsInstance<ContentBlock.ToolResult>().map { it.toolUseId })
    }

    private fun results(request: ModelRequest): Map<ToolCallId, ContentBlock.ToolResult> =
        request.messages.flatMap { it.toolResults }.associateBy { it.toolUseId }

    /**
     * File tools that take their time. With [pressCtrlCWhileRunning], Ctrl-C arrives while the first
     * call is still running, from another thread, as SIGINT does; the call still finishes.
     */
    private inner class SlowFiles : WorkspaceGateway {
        var pressCtrlCWhileRunning = false
        val invocations = mutableListOf<ToolInvocation>()

        override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome {
            invocations += invocation
            if (pressCtrlCWhileRunning && invocations.size == 1) thread(name = "SIGINT handler") { signals.press() }.join()
            return ToolOutcome("# Demo")
        }
    }

    /**
     * Answers each prompt with the next scripted line, printing the prompt as the plain reader does.
     * [CTRL_C] acts as that key does in the JLine console on an empty line: it reaches the running
     * turn's handler and reads as null.
     */
    private inner class ScriptedConsole(override val interactive: Boolean, vararg script: String) : CliConsole {
        private val pending = ArrayDeque(script.toList())
        private val outBuffer = ByteArrayOutputStream()
        private val errBuffer = ByteArrayOutputStream()

        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val out = PrintStream(outBuffer, true, Charsets.UTF_8)
        override val err = PrintStream(errBuffer, true, Charsets.UTF_8)

        override fun readLine(prompt: String): String? {
            out.print(prompt)
            val next = pending.removeFirstOrNull() ?: return null
            if (next != CTRL_C) return next
            signals.press()
            return null
        }

        val printed: String
            get() = outBuffer.toString(Charsets.UTF_8).replace(System.lineSeparator(), "\n")

        val errors: String
            get() = errBuffer.toString(Charsets.UTF_8).replace(System.lineSeparator(), "\n")
    }

    private companion object {
        const val CTRL_C = "<Ctrl-C>"

        /** Core's result, in Clawcode's words, for a call the turn was cancelled before it could run. */
        const val INTERRUPTED_RESULT = "Interrupted by user before this tool could run."
    }
}
