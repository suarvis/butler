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
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.fixtures.CancelAfter
import com.suarvis.butler.core.fixtures.FakeMcpGateway
import com.suarvis.butler.core.fixtures.FakeToolInputGateway
import com.suarvis.butler.core.fixtures.RecordingCommandGateway
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

    private fun offered(
        tool: ToolDefinition,
        input: String = "{}",
        cancellation: TurnCancellation = TurnCancellation.NEVER,
    ) = RunToolCallUseCase.Command(workspace, callTo(tool.name, input), tool, PermissionMode.PROMPT, PermissionRules.NONE, cancellation)

    private fun assertRunsOnFiles(tool: ToolDefinition, invocation: ToolInvocation) {
        val result = useCase(decodingTo(invocation)).execute(offered(tool))

        assertEquals(ContentBlock.ToolResult(callId, tool.name, "file tool ran", isError = false), result)
        assertEquals(listOf(invocation), files.invocations)
        assertTrue(shell.commands.isEmpty(), "the shell is not used")
        assertTrue(mcp.calls.isEmpty(), "MCP is not used")
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

        val result = useCase(decodingTo(bash)).execute(offered(BuiltInTools.BASH, cancellation = cancellation))

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

        val result = useCase(inputs).execute(offered(search, input = """{"q":"budget"}"""))

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

        useCase(inputs).execute(offered(BuiltInTools.READ_FILE, input = """{"path":"README.md"}"""))

        assertEquals(listOf(BuiltInTools.READ_FILE.name to JsonText("""{"path":"README.md"}""")), decoded)
    }

    @Test
    fun `an error outcome comes back as an error result, without its preview`() {
        val missing = RecordingWorkspaceGateway(ToolOutcome("No such file: README.md", isError = true, preview = "README.md"))

        val result = useCase(decodingTo(ToolInvocation.ReadFile(readme)), missing).execute(offered(BuiltInTools.READ_FILE))

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
            )

        assertEquals(ContentBlock.ToolResult(callId, unknown, "unsupported tool: delete_everything", isError = true), result)
        assertTrue(decoded.isEmpty(), "nothing is decoded")
        assertTrue(shell.commands.isEmpty(), "nothing runs")
    }

    @Test
    fun `input that does not decode comes back as the decoder's reason and runs nothing`() {
        val inputs = FakeToolInputGateway { name, _ -> throw InvalidToolInputException(name, "missing field path") }

        val result = useCase(inputs).execute(offered(BuiltInTools.READ_FILE))

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

        val result = useCase(decodingTo(ToolInvocation.WriteFile(readme, "# Demo")), unplugged).execute(offered(BuiltInTools.WRITE_FILE))

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.WRITE_FILE.name, "Tool write_file failed: disk unplugged", isError = true), result)
    }

    @Test
    fun `a port that throws without a message is named by the exception's type`() {
        val broken =
            object : WorkspaceGateway {
                override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome = throw IllegalStateException()
            }

        val result = useCase(decodingTo(ToolInvocation.ReadFile(readme)), broken).execute(offered(BuiltInTools.READ_FILE))

        assertEquals(ContentBlock.ToolResult(callId, BuiltInTools.READ_FILE.name, "Tool read_file failed: IllegalStateException", isError = true), result)
    }
}
