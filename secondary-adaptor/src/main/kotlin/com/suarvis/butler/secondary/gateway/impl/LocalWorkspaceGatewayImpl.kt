package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.tool.BuiltInTools
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.domain.tool.ToolOutcome
import com.suarvis.butler.core.gateway.WorkspaceGateway
import com.suarvis.butler.secondary.workspace.support.AtomicWrite
import com.suarvis.butler.secondary.workspace.support.EditFileTool
import com.suarvis.butler.secondary.workspace.support.GlobSearchTool
import com.suarvis.butler.secondary.workspace.support.GrepSearchTool
import com.suarvis.butler.secondary.workspace.support.IoErrorMessages
import com.suarvis.butler.secondary.workspace.support.PathGuard
import com.suarvis.butler.secondary.workspace.support.ReadFileTool
import com.suarvis.butler.secondary.workspace.support.WorkspaceToolException
import com.suarvis.butler.secondary.workspace.support.WriteFileTool
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.InvalidPathException
import java.time.Duration

/**
 * Runs the file tools on the local disk with Clawcode's limits and JSON envelopes
 * (`runtime/src/file_ops.rs`). Every path is resolved against the workspace and its real path
 * checked, so a symlink cannot lead a tool outside. Failures the model can act on, such as a
 * missing file, a binary file or a bad pattern, come back as error outcomes with Clawcode's
 * wording, never as exceptions.
 *
 * `write_file` and `edit_file` replace files atomically, and both their outcome and [preview]
 * carry the change as a unified diff. `bash` and MCP tools belong to other gateways, so they are
 * refused here.
 */
class LocalWorkspaceGatewayImpl internal constructor(
    private val grepFileDeadline: Duration,
    private val writer: AtomicWrite,
) : WorkspaceGateway {
    /** @param grepFileDeadline how long `grep_search` may match one file before skipping it */
    constructor(grepFileDeadline: Duration = GrepSearchTool.FILE_DEADLINE) : this(grepFileDeadline, AtomicWrite())

    override fun run(workspace: WorkspaceRoot, invocation: ToolInvocation): ToolOutcome =
        try {
            when (invocation) {
                is ToolInvocation.ReadFile -> ToolOutcome(ReadFileTool(PathGuard(workspace)).run(invocation))
                is ToolInvocation.GlobSearch -> ToolOutcome(GlobSearchTool(PathGuard(workspace)).run(invocation))
                is ToolInvocation.GrepSearch -> ToolOutcome(GrepSearchTool(PathGuard(workspace), grepFileDeadline).run(invocation))
                is ToolInvocation.WriteFile -> WriteFileTool(PathGuard(workspace), writer).run(invocation)
                is ToolInvocation.EditFile -> EditFileTool(PathGuard(workspace), writer).run(invocation)
                is ToolInvocation.Bash -> failure("${BuiltInTools.BASH.name} is not a workspace file tool")
                is ToolInvocation.External -> failure("${invocation.tool} is not a workspace file tool")
            }
        } catch (refused: WorkspaceToolException) {
            failure(refused.message)
        } catch (failed: IOException) {
            failure(IoErrorMessages.describe(failed))
        } catch (failed: UncheckedIOException) {
            failure(failed.cause?.let(IoErrorMessages::describe) ?: failed.message.orEmpty())
        } catch (invalid: InvalidPathException) {
            failure(invalid.message ?: invalid.reason)
        }

    /**
     * The unified diff `write_file` or `edit_file` would make, after the same checks as [run] and
     * without touching the disk. Null for the other tools, for a write that changes nothing, and
     * for a call [run] would refuse: the refusal reaches the model when the call runs, and a
     * preview must never fail the call.
     */
    override fun preview(workspace: WorkspaceRoot, invocation: ToolInvocation): String? =
        try {
            when (invocation) {
                is ToolInvocation.WriteFile -> WriteFileTool(PathGuard(workspace), writer).preview(invocation)
                is ToolInvocation.EditFile -> EditFileTool(PathGuard(workspace), writer).preview(invocation)
                else -> null
            }
        } catch (refused: WorkspaceToolException) {
            null
        } catch (failed: IOException) {
            null
        } catch (failed: UncheckedIOException) {
            null
        } catch (invalid: InvalidPathException) {
            null
        }

    private fun failure(message: String): ToolOutcome = ToolOutcome(message, isError = true)
}
