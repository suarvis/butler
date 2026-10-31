package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.domain.exception.InvalidToolInputException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspacePath
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.permission.ToolPattern
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.BashRiskClassifier
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.ToolApprovalDecision
import com.suarvis.butler.core.domain.turn.ToolApprovalRequest
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.fixtures.CancelAfter
import com.suarvis.butler.core.fixtures.FakeMcpGateway
import com.suarvis.butler.core.fixtures.FakeToolInputGateway
import com.suarvis.butler.core.fixtures.RecordingCommandGateway
import com.suarvis.butler.core.fixtures.RecordingToolApprover
import com.suarvis.butler.core.fixtures.RecordingWorkspaceGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.gateway.CommandGateway
import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RunToolCallUseCaseTest {
    private val workspace = SessionFixtures.workspace
    private val callId = ToolCallId("toolu_01")
    private val readme = WorkspacePath("README.md")
    private val search =
        ToolDefinition(ToolName("mcp__docs__search"), "Search the docs.", JsonText("""{"type":"object"}"""), PermissionTier.READ_ONLY)

    private val files = RecordingWorkspaceGateway(ToolOutcome("file tool ran"))
    private val shell = RecordingCommandGateway(ToolOutcome("command ran"))
    private val commands = CancellationRecordingCommandGateway(shell)
    private val mcp = FakeMcpGateway(tools = listOf(search), outcomes = mapOf(search.name to ToolOutcome("3 hits")))
    private val approver = RecordingToolApprover()

    /** Keeps the cancellation each command was given, which [RecordingCommandGateway] does not record. */
    private class CancellationRecordingCommandGateway(private val delegate: CommandGateway) : CommandGateway {
        val cancellations = mutableListOf<TurnCancellation>()

        override fun run(
            workspace: WorkspaceRoot,
            command: ToolInvocation.Bash,
            sandbox: SandboxPolicy,
            cancellation: TurnCancellation,
        ): ToolOutcome {
            cancellations += cancellation
            return delegate.run(workspace, command, sandbox, cancellation)
        }
    }

    private fun useCase(inputs: ToolInputGateway, files: WorkspaceGateway = this.files) = RunToolCallUseCase(inputs, files, commands, mcp)

    private fun decodingTo(invocation: ToolInvocation) = FakeToolInputGateway { _, _ -> invocation }

    private fun callTo(name: ToolName, input: String = "{}") = ContentBlock.ToolUse(callId, name, JsonText(input))

    /** Routing tests run in `allow` mode, so the permission step lets every call through without asking. */
    private fun offered(
        tool: ToolDefinition,
        input: String = "{}",
        cancellation: TurnCancellation = TurnCancellation.NEVER,
        mode: PermissionMode = PermissionMode.ALLOW,
        rules: PermissionRules = PermissionRules.NONE,
    ) = RunToolCallUseCase.Command(workspace, callTo(tool.name, input), tool, mode, rules, cancellation)

    private fun assertRunsOnFiles(tool: ToolDefinition, invocation: ToolInvocation) {
        val result = useCase(decodingTo(invocation)).execute(offered(tool), approver)

        assertEquals(ContentBlock.ToolResult(callId, tool.name, "file tool ran", isError = false), result)
        assertEquals(listOf(invocation), files.invocations)
        assertTrue(shell.commands.isEmpty(), "the shell is not used")
        assertTrue(mcp.calls.isEmpty(), "MCP is not used")
        assertTrue(approver.requests.isEmpty(), "nobody is asked")
    }

    @Test
    fun `read_file runs on the workspace gateway`() {
        assertRunsOnFiles(BuiltInTools.READ_FILE, ToolInvocation.ReadFile(readme, offset = 10, limit = 20))
    }

    @Test
    fun `glob_search runs on the workspace gateway`() {
        assertRunsOnFiles(BuiltInTools.GLOB_SEARCH, ToolInvocation.GlobSearch("**/*.kt"))
    }

    @Test
    fun `grep_search runs on the workspace gateway`() {
        assertRunsOnFiles(BuiltInTools.GREP_SEARCH, ToolInvocation.GrepSearch("TODO", ignoreCase = true))
    }

    @Test
    fun `write_file runs on the workspace gateway`() {
        assertRunsOnFiles(BuiltInTools.WRITE_FILE, ToolInvocation.WriteFile(readme, "# Demo"))
    }

    @Test
    fun `edit_file runs on the workspace gateway`() {
        assertRunsOnFiles(BuiltInTools.EDIT_FILE, ToolInvocation.EditFile(readme, "old", "new"))
    }

    @Test
    fun `bash runs on the command gateway with the workspace sandbox and the turn's cancellation`() {
        val bash = ToolInvocation.Bash("ls", timeout = Duration.ofSeconds(5))
        val cancellation = CancelAfter(1)

        val result = useCase(decodingTo(bash)).execute(offered(BuiltInTools.BASH, cancellation = cancellation), approver)

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.BASH.name, "command ran", isError = false), result)
        assertEquals(listOf(bash), shell.commands)
        assertEquals(listOf(SandboxPolicy.forWorkspace(workspace)), shell.sandboxes)
        assertSame(cancellation, commands.cancellations.single())
        assertTrue(files.invocations.isEmpty(), "the file tools are not used")
        assertTrue(mcp.calls.isEmpty(), "MCP is not used")
    }

    @Test
    fun `an MCP tool runs on the MCP gateway with its input untouched`() {
        val inputs = FakeToolInputGateway { name, input -> ToolInvocation.External(name, input) }

        val result = useCase(inputs).execute(offered(search, input = """{"q":"budget"}"""), approver)

        assertEquals(ContentBlock.ToolResult(callId, search.name, "3 hits", isError = false), result)
        assertEquals(listOf(FakeMcpGateway.Call(search.name, JsonText("""{"q":"budget"}"""))), mcp.calls)
        assertTrue(files.invocations.isEmpty(), "the file tools are not used")
        assertTrue(shell.commands.isEmpty(), "the shell is not used")
    }

    @Test
    fun `the call's name and raw input are what gets decoded`() {
        val decoded = mutableListOf<Pair<ToolName, JsonText>>()
        val inputs =
            FakeToolInputGateway { name, input ->
                decoded += name to input
                ToolInvocation.ReadFile(readme)
            }

        useCase(inputs).execute(offered(BuiltInTools.READ_FILE, input = """{"path":"README.md"}"""), approver)

        assertEquals(listOf(BuiltInTools.READ_FILE.name to JsonText("""{"path":"README.md"}""")), decoded)
    }

    @Test
    fun `an error outcome comes back as an error result, without its preview`() {
        val missing = RecordingWorkspaceGateway(ToolOutcome("No such file: README.md", isError = true, preview = "README.md"))

        val result = useCase(decodingTo(ToolInvocation.ReadFile(readme)), missing).execute(offered(BuiltInTools.READ_FILE), approver)

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.READ_FILE.name, "No such file: README.md", isError = true), result)
    }

    @Test
    fun `a tool the turn did not offer is refused without decoding or running anything`() {
        val decoded = mutableListOf<ToolName>()
        val inputs =
            FakeToolInputGateway { name, _ ->
                decoded += name
                ToolInvocation.Bash("ls")
            }
        val unknown = ToolName("delete_everything")

        val result =
            useCase(inputs).execute(
                RunToolCallUseCase.Command(workspace, callTo(unknown), tool = null, PermissionMode.PROMPT, PermissionRules.NONE),
                approver,
            )

        assertEquals(ContentBlock.ToolResult(callId, unknown, "unsupported tool: delete_everything", isError = true), result)
        assertTrue(decoded.isEmpty(), "nothing is decoded")
        assertTrue(shell.commands.isEmpty(), "nothing runs")
        assertTrue(approver.requests.isEmpty(), "nobody is asked")
    }

    @Test
    fun `input that does not decode comes back as the decoder's reason and runs nothing`() {
        val inputs = FakeToolInputGateway { name, _ -> throw InvalidToolInputException(name, "missing field path") }

        val result = useCase(inputs).execute(offered(BuiltInTools.READ_FILE), approver)

        assertEquals(
            ContentBlock.ToolResult(callId, BuiltInTools.READ_FILE.name, "Invalid input for read_file: missing field path", isError = true),
            result,
        )
        assertTrue(files.invocations.isEmpty(), "nothing runs")
    }

    @Test
    fun `a port that throws gives a failed result naming the tool`() {
        val unplugged =
            object : WorkspaceGateway {
                override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome = throw GatewayException("disk unplugged")
            }

        val result = useCase(decodingTo(ToolInvocation.WriteFile(readme, "# Demo")), unplugged).execute(offered(BuiltInTools.WRITE_FILE), approver)

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "Tool write_file failed: disk unplugged", isError = true), result)
    }

    @Test
    fun `a port that throws without a message is named by the exception's type`() {
        val broken =
            object : WorkspaceGateway {
                override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome = throw IllegalStateException()
            }

        val result = useCase(decodingTo(ToolInvocation.ReadFile(readme)), broken).execute(offered(BuiltInTools.READ_FILE), approver)

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.READ_FILE.name, "Tool read_file failed: IllegalStateException", isError = true), result)
    }

    // The permission step (A5)

    @Test
    fun `a call the mode covers runs without asking`() {
        val result = useCase(decodingTo(ToolInvocation.ReadFile(readme))).execute(offered(BuiltInTools.READ_FILE, mode = PermissionMode.PROMPT), approver)

        assertEquals("file tool ran", result.output)
        assertTrue(approver.requests.isEmpty(), "prompt mode lets read-only tools through")
    }

    @Test
    fun `a call the policy denies returns its reason as an error, without asking or running`() {
        val result =
            useCase(decodingTo(ToolInvocation.WriteFile(readme, "# Demo"))).execute(offered(BuiltInTools.WRITE_FILE, mode = PermissionMode.READ_ONLY), approver)

        assertEquals(
            ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "tool 'write_file' requires workspace-write permission; current mode is read-only", isError = true),
            result,
        )
        assertTrue(approver.requests.isEmpty(), "a policy denial is final")
        assertTrue(files.invocations.isEmpty(), "nothing runs")
    }

    @Test
    fun `a deny rule is applied to the call`() {
        val rules = PermissionRules(deny = listOf(ToolPattern(BuiltInTools.BASH.name, "rm:*")))

        val result = useCase(decodingTo(ToolInvocation.Bash("rm -rf build"))).execute(offered(BuiltInTools.BASH, rules = rules), approver)

        assertEquals("Permission to use bash has been denied by rule 'bash(rm:*)'", result.output)
        assertTrue(result.isError)
        assertTrue(shell.commands.isEmpty(), "nothing runs")
        assertTrue(approver.requests.isEmpty(), "a rule denial is final")
    }

    @Test
    fun `a call the policy asks about goes to the approver once with the tier, mode, reason and the edit's preview`() {
        val write = ToolInvocation.WriteFile(readme, "# Demo")
        val diffing = RecordingWorkspaceGateway(ToolOutcome("file tool ran"), previews = mapOf(write to "+# Demo"))
        val approving = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)

        val result = useCase(decodingTo(write), diffing).execute(offered(BuiltInTools.WRITE_FILE, mode = PermissionMode.PROMPT), approving)

        assertEquals(
            ToolApprovalRequest(
                callTo(BuiltInTools.WRITE_FILE.name),
                write,
                PermissionTier.WORKSPACE_WRITE,
                PermissionMode.PROMPT,
                "tool 'write_file' requires approval to escalate from prompt to workspace-write",
                preview = "+# Demo",
                bashRisk = null,
            ),
            approving.requests.single(),
        )
        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "file tool ran", isError = false), result)
        assertEquals(listOf<ToolInvocation>(write), diffing.invocations)
    }

    @Test
    fun `allow for this session runs the call like allow once`() {
        val approving = RecordingToolApprover(ToolApprovalDecision.ALLOW_FOR_SESSION)

        val result =
            useCase(decodingTo(ToolInvocation.EditFile(readme, "old", "new"))).execute(offered(BuiltInTools.EDIT_FILE, mode = PermissionMode.PROMPT), approving)

        assertEquals("file tool ran", result.output)
        assertEquals(1, approving.requests.size)
    }

    @Test
    fun `a call the person denies returns Clawcode's denial and runs nothing`() {
        val denying = RecordingToolApprover(ToolApprovalDecision.DENY)

        val result =
            useCase(decodingTo(ToolInvocation.WriteFile(readme, "# Demo"))).execute(offered(BuiltInTools.WRITE_FILE, mode = PermissionMode.PROMPT), denying)

        assertEquals(
            ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "tool 'write_file' denied by user approval prompt", isError = true),
            result,
        )
        assertTrue(files.invocations.isEmpty(), "nothing runs")
    }

    @Test
    fun `a bash call is judged by its command's risk, which the approver sees with the command as preview`() {
        val command = "rm -rf build"
        val risk = BashRiskClassifier.classify(command, workspace, sandboxActive = false)
        val approving = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)

        val result = useCase(decodingTo(ToolInvocation.Bash(command))).execute(offered(BuiltInTools.BASH, mode = PermissionMode.PROMPT), approving)

        val request = approving.requests.single()
        assertEquals(risk, request.bashRisk)
        assertEquals(risk.tier, request.tier)
        assertEquals(command, request.preview)
        assertEquals("command ran", result.output)
        assertEquals(listOf(ToolInvocation.Bash(command)), shell.commands)
    }

    @Test
    fun `a read-only bash command runs in prompt mode without asking`() {
        val result = useCase(decodingTo(ToolInvocation.Bash("ls"))).execute(offered(BuiltInTools.BASH, mode = PermissionMode.PROMPT), approver)

        assertEquals("command ran", result.output)
        assertTrue(approver.requests.isEmpty(), "ls needs only read-only, whatever bash's own tier")
    }

    @Test
    fun `an MCP call the user's ask rule catches shows its raw input as preview`() {
        val inputs = FakeToolInputGateway { name, input -> ToolInvocation.External(name, input) }
        val rules = PermissionRules(ask = listOf(ToolPattern(search.name)))
        val approving = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)

        val result = useCase(inputs).execute(offered(search, input = """{"q":"budget"}""", rules = rules), approving)

        val request = approving.requests.single()
        assertEquals("tool 'mcp__docs__search' requires approval due to ask rule 'mcp__docs__search'", request.reason)
        assertEquals("""{"q":"budget"}""", request.preview)
        assertEquals(PermissionTier.READ_ONLY, request.tier)
        assertEquals("3 hits", result.output)
    }

    @Test
    fun `a turn cancelled while the approver was asked gives Clawcode's interrupted result and runs nothing`() {
        val approving = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE)

        val result =
            useCase(decodingTo(ToolInvocation.WriteFile(readme, "# Demo")))
                .execute(offered(BuiltInTools.WRITE_FILE, mode = PermissionMode.PROMPT, cancellation = CancelAfter(0)), approving)

        assertEquals(
            ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "Interrupted by user before this tool could run.", isError = true),
            result,
        )
        assertEquals(1, approving.requests.size)
        assertTrue(files.invocations.isEmpty(), "nothing runs")
    }
}
