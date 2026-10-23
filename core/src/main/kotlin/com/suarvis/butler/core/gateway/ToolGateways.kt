package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.context.ProjectContext
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.shell.SandboxPolicy
import com.suarvis.butler.core.domain.tool.ToolDefinition
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.domain.turn.TurnCancellation

/**
 * Turns a tool call's JSON input into a typed [ToolInvocation]. A port because core has no JSON
 * library; paths are checked against [WorkspaceRoot] here so nothing later sees an escape.
 * Implemented by lane C.
 */
interface ToolInputGateway {
    /** @throws com.suarvis.butler.core.domain.exception.InvalidToolInputException naming the field that is wrong */
    fun decode(workspace: WorkspaceRoot, name: ToolName, input: JsonText): ToolInvocation
}

/**
 * Runs the file tools (read, write, edit, glob, grep) against the workspace on disk. Failures the
 * model can act on, such as a missing file, come back as an error [ToolOutcome], not an exception.
 * Implemented by lane C.
 */
interface WorkspaceGateway {
    fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome

    /** What an approval prompt shows before the tool runs, such as an edit's diff; null when there is nothing to show. */
    fun preview(workspace: WorkspaceRoot, invocation: ToolInvocation): String? = null
}

/**
 * Runs a shell command inside [SandboxPolicy] limits. It polls [TurnCancellation] while the command
 * runs, so a cancelled turn stops the process instead of waiting for it. Implemented by lane F.
 */
interface CommandGateway {
    fun run(
        workspace: WorkspaceRoot,
        command: ToolInvocation.Bash,
        sandbox: SandboxPolicy,
        cancellation: TurnCancellation,
    ): ToolOutcome
}

/** Tools offered by configured MCP servers, and calls to them. Implemented by lane H. */
interface McpGateway {
    fun tools(): List<ToolDefinition>

    fun call(tool: ToolName, input: JsonText): ToolOutcome
}

/** Reads instruction files and git state for the system prompt. Implemented by lane G. */
interface ProjectContextGateway {
    fun load(workspace: WorkspaceRoot): ProjectContext
}
