package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.gateway.SessionGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour every [SessionGateway] implementation must share. Each storage technology runs the
 * same tests, which is what lets the CLI switch stores with one setting.
 */
abstract class SessionGatewayContract {
    protected abstract val workspace: WorkspaceRoot

    protected abstract fun gateway(): SessionGateway

    private fun session(id: String, secondsAfterStart: Long) =
        SessionFixtures.sessionWithToolRoundTrip(id, SessionFixtures.start.plusSeconds(secondsAfterStart), workspace)

    @Test
    fun `a saved session reads back identical`() {
        val gateway = gateway()
        val original = session("session-a", 0)

        gateway.save(original)

        assertEquals(original, gateway.findById(workspace, original.id))
    }

    @Test
    fun `saving again replaces the stored transcript`() {
        val gateway = gateway()
        val original = session("session-a", 0)
        gateway.save(original)

        val extended = original.appendUserPrompt("one more thing", original.updatedAt.plusSeconds(10))
        gateway.save(extended)

        assertEquals(extended, gateway.findById(workspace, original.id))
    }

    @Test
    fun `forks keep their lineage`() {
        val gateway = gateway()
        val parent = session("session-a", 0)
        val fork = parent.fork(SessionId("session-b"), BranchName("try-b"), parent.updatedAt.plusSeconds(1))

        gateway.save(fork)

        assertEquals(fork, gateway.findById(workspace, fork.id))
    }

    @Test
    fun `unknown ids return null`() {
        assertNull(gateway().findById(workspace, SessionId("missing")))
        assertNull(gateway().findLatest(workspace))
    }

    @Test
    fun `latest is the most recently updated session`() {
        val gateway = gateway()
        gateway.save(session("session-old", 0))
        gateway.save(session("session-new", 100))

        assertEquals(SessionId("session-new"), gateway.findLatest(workspace)?.id)
    }

    @Test
    fun `summaries are paged newest first with a total`() {
        val gateway = gateway()
        listOf("session-1" to 10L, "session-2" to 20L, "session-3" to 30L).forEach { (id, at) -> gateway.save(session(id, at)) }

        val first = gateway.findSummaries(workspace, PageRequest(offset = 0, limit = 2))
        val second = gateway.findSummaries(workspace, PageRequest(offset = 2, limit = 2))

        assertEquals(listOf("session-3", "session-2"), first.items.map { it.id.value })
        assertEquals(3, first.totalItems)
        assertTrue(first.hasNext)
        assertEquals(listOf("session-1"), second.items.map { it.id.value })
        assertFalse(second.hasNext)
        assertEquals(4, first.items.first().messageCount)
    }

    @Test
    fun `delete removes the session and reports whether it existed`() {
        val gateway = gateway()
        gateway.save(session("session-a", 0))

        assertTrue(gateway.delete(workspace, SessionId("session-a")))
        assertFalse(gateway.delete(workspace, SessionId("session-a")))
        assertNull(gateway.findById(workspace, SessionId("session-a")))
    }

    @Test
    fun `sessions in another workspace are invisible`() {
        val gateway = gateway()
        gateway.save(session("session-a", 0))
        val elsewhere = WorkspaceRoot.of(workspace.path.resolveSibling(workspace.path.fileName.toString() + "-other"))

        assertNull(gateway.findById(elsewhere, SessionId("session-a")))
        assertEquals(0, gateway.findSummaries(elsewhere, PageRequest.firstPage()).totalItems)
    }
}
