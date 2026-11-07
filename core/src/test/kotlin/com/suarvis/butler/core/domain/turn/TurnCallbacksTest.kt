package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.PermissionMode
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.tool.PermissionTier
import com.suarvis.butler.core.domain.tool.ToolInvocation
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import com.suarvis.butler.core.fixtures.RecordingToolApprover
import com.suarvis.butler.core.fixtures.RecordingTurnApprover
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
    fun `a turn may make 32 model calls unless configured otherwise`() {
        assertEquals(32, TurnSettings().maxIterations)
        assertEquals(1, TurnSettings(maxIterations = 1).maxIterations)
    }

    @Test
    fun `an iteration limit below one is refused`() {
        listOf(0, -5).forEach { limit ->
            val error = assertThrows<DomainValidationException> { TurnSettings(toolsEnabled = true, maxIterations = limit) }
            assertEquals("butler.tools.max-iterations must be at least 1, got $limit", error.message)
        }
    }

    @Test
    fun `the recording tool approver answers in order, then denies`() {
        val approver = RecordingToolApprover(ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_FOR_SESSION)

        val answers = listOf("ls", "pwd", "rm -rf build").map { approver.decide(bashRequest(it)) }

        assertEquals(listOf(ToolApprovalDecision.ALLOW_ONCE, ToolApprovalDecision.ALLOW_FOR_SESSION, ToolApprovalDecision.DENY), answers)
        assertEquals(listOf("ls", "pwd", "rm -rf build"), approver.requests.map { it.preview })
    }

    @Test
    fun `the recording turn approver answers in order, then stops`() {
        val catalog = FixedModelCatalog()
        val request = ApprovalRequest(catalog.resolve(ModelId("sonnet")), emptyList(), listOf(catalog.resolve(ModelId("gpt-5.5"))))
        val approver = RecordingTurnApprover({ ApprovalDecision.Continue }, { asked -> ApprovalDecision.SwitchTo(asked.alternatives.single()) })

        val answers = List(3) { approver.decide(request) }

        assertEquals(listOf(ApprovalDecision.Continue, ApprovalDecision.SwitchTo(request.alternatives.single()), ApprovalDecision.Stop), answers)
        assertEquals(3, approver.requests.size)
    }
}
