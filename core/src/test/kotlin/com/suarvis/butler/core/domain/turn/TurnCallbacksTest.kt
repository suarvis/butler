package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.fixtures.RecordingToolApprover
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TurnCallbacksTest {
    private fun bashRequest(command: String) =
        ToolApprovalRequest(
            call = ContentBlock.ToolUse(ToolCallId("toolu_$command"), ToolName("bash"), JsonText("""{"command":"$command"}""")),
            invocation = ToolInvocation.Bash(command),
            tier = PermissionTier.DANGER_FULL_ACCESS,
            mode = PermissionMode.PROMPT,
            reason = "bash needs approval",
            preview = command,
        )

    @Test
    fun `by default nobody is asked, so limits stop the turn, tools are denied and nothing cancels`() {
        val callbacks = TurnCallbacks()

        assertSame(TurnObserver.NONE, callbacks.observer)
        assertSame(TurnApprover.STOP, callbacks.approver)
        assertSame(ToolApprover.DENY, callbacks.toolApprover)
        assertSame(TurnCancellation.NEVER, callbacks.cancellation)
        assertEquals(ToolApprovalDecision.DENY, callbacks.toolApprover.decide(bashRequest("ls")))
        assertFalse(callbacks.cancellation.isCancelled())
    }

    @Test
    fun `tools are disabled unless configuration enables them`() {
        assertFalse(TurnSettings().toolsEnabled)
    }

    @Test
    fun `the recording tool approver answers in order, then denies`() {
        val approver = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_FOR_SESSION)

        val answers = listOf("ls", "pwd", "rm -rf build").map { approver.decide(bashRequest(it)) }

        assertEquals(listOf(ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_FOR_SESSION, ToolApprovalDecision.DENY), answers)
        assertEquals(listOf("ls", "pwd", "rm -rf build"), approver.requests.map { it.preview })
    }
}
