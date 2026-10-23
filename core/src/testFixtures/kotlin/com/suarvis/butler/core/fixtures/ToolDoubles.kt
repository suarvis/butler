package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation
import com.suarvis.butler.core.gateway.CommandGateway
import com.suarvis.butler.core.gateway.McpGateway
import com.suarvis.butler.core.gateway.ProjectContextGateway
import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway

/** Decodes with [decoder], so a test states the invocation it wants instead of parsing JSON. */
class FakeToolInputGateway(private val decoder: (ToolName, JsonText) -> ToolInvocation) : ToolInputGateway {
    override fun decode(workspace: WorkspaceRoot, name: ToolName, input: JsonText): ToolInvocation = decoder(name, input)
}

/** File tools that answer with scripted outcomes in order and record every invocation they receive. */
class RecordingWorkspaceGateway(
    vararg outcomes: ToolOutcome,
    private val previews: Map<ToolInvocation, String> = emptyMap(),
) : WorkspaceGateway {
    private val pending = ArrayDeque(outcomes.toList())
    val invocations = mutableListOf<ToolInvocation>()

    override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome {
        invocations += invocation
        return pending.removeFirstOrNull() ?: error("no scripted outcome left")
    }

    override fun preview(workspace: WorkspaceRoot, invocation: ToolInvocation): String? = previews[invocation]
}

/** Shell that answers with scripted outcomes in order and records each command with the sandbox it was given. */
class RecordingCommandGateway(vararg outcomes: ToolOutcome) : CommandGateway {
    private val pending = ArrayDeque(outcomes.toList())
    val commands = mutableListOf<ToolInvocation.Bash>()
    val sandboxes = mutableListOf<SandboxPolicy>()

    override fun run(
        workspace: WorkspaceRoot,
        command: ToolInvocation.Bash,
        sandbox: SandboxPolicy,
        cancellation: TurnCancellation,
    ): ToolOutcome {
        commands += command
        sandboxes += sandbox
        return pending.removeFirstOrNull() ?: error("no scripted outcome left")
    }
}

/** MCP servers offering [tools]; each call is recorded and answered from [outcomes], or with an error for a tool not in it. */
class FakeMcpGateway(
    tools: List<ToolDefinition> = emptyList(),
    private val outcomes: Map<ToolName, ToolOutcome> = emptyMap(),
) : McpGateway {
    data class Call(val tool: ToolName, val input: JsonText)

    private val offered = tools
    val calls = mutableListOf<Call>()

    override fun tools(): List<ToolDefinition> = offered

    override fun call(tool: ToolName, input: JsonText): ToolOutcome {
        calls += Call(tool, input)
        return outcomes[tool] ?: ToolOutcome("Unknown MCP tool $tool", isError = true)
    }
}

class FixedProjectContextGateway(var context: ProjectContext = ProjectContext.EMPTY) : ProjectContextGateway {
    val workspaces = mutableListOf<WorkspaceRoot>()

    override fun load(workspace: WorkspaceRoot): ProjectContext {
        workspaces += workspace
        return context
    }
}

/** Not cancelled for the first [checks] polls, cancelled from then on: stops a turn at a chosen step. */
class CancelAfter(private val checks: Int) : TurnCancellation {
    var polls = 0
        private set

    override fun isCancelled(): Boolean = polls++ >= checks
}
