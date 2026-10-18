package com.suarvis.butler.core.usecase.session

import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.generic.BranchName
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.PageRequest
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.fixtures.InMemorySessionGateway
import com.suarvis.butler.core.fixtures.MutableClock
import com.suarvis.butler.core.fixtures.SequentialIdentityGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** Use cases run against in-memory fakes: no files, no database, no Spring. */
class SessionUseCasesTest {
    private val gateway = InMemorySessionGateway()
    private val identity = SequentialIdentityGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val workspace = SessionFixtures.workspace

    private val start = StartSessionUseCase(gateway, identity, clock)
    private val get = GetSessionUseCase(gateway)
    private val list = ListSessionsUseCase(gateway)
    private val fork = ForkSessionUseCase(get, gateway, identity, clock)
    private val delete = DeleteSessionUseCase(get, gateway)

    @Test
    fun `starting a session saves it with a fresh id`() {
        val session = start.execute(workspace, ModelId("opus"))

        assertEquals(SessionId("session-test-1"), session.id)
        assertEquals(ModelId("opus"), session.model)
        assertEquals(listOf(session), gateway.saved)
    }

    @Test
    fun `latest resolves to the most recently updated session`() {
        start.execute(workspace)
        clock.advance(Duration.ofMinutes(1))
        val newer = start.execute(workspace)

        assertEquals(newer, get.execute(workspace, SessionReference.Latest))
    }

    @Test
    fun `missing sessions raise a not-found error`() {
        val error = assertThrows<SessionNotFoundException> { get.execute(workspace, SessionReference.Latest) }

        assertEquals(SessionReference.Latest, error.reference)
        assertNull(get.findOrNull(workspace, SessionReference.ById(SessionId("nope"))))
    }

    @Test
    fun `listing pages through sessions newest first`() {
        repeat(3) {
            start.execute(workspace)
            clock.advance(Duration.ofSeconds(1))
        }

        val firstPage = list.execute(workspace, PageRequest(offset = 0, limit = 2))
        val secondPage = list.execute(workspace, PageRequest(offset = 2, limit = 2))

        assertEquals(listOf("session-test-3", "session-test-2"), firstPage.items.map { it.id.value })
        assertTrue(firstPage.hasNext)
        assertEquals(listOf("session-test-1"), secondPage.items.map { it.id.value })
        assertFalse(secondPage.hasNext)
        assertEquals(3, list.count(workspace))
    }

    @Test
    fun `sessions are scoped to their workspace`() {
        start.execute(workspace)
        val other = WorkspaceRoot.of(workspace.path.resolveSibling("other"))

        assertEquals(0, list.count(other))
        assertNull(get.findOrNull(other, SessionReference.Latest))
    }

    @Test
    fun `forking saves a copy that points at its parent`() {
        gateway.save(SessionFixtures.sessionWithToolRoundTrip(id = "session-parent"))

        val forked = fork.execute(workspace, SessionReference.ById(SessionId("session-parent")), BranchName("experiment"))

        assertEquals(SessionId("session-parent"), forked.fork?.parentSessionId)
        assertEquals(BranchName("experiment"), forked.fork?.branchName)
        assertEquals(forked, gateway.findById(workspace, forked.id))
        assertEquals(4, forked.messageCount)
    }

    @Test
    fun `deleting latest removes exactly that session`() {
        start.execute(workspace)
        clock.advance(Duration.ofSeconds(1))
        val newest = start.execute(workspace)

        val deleted = delete.execute(workspace, SessionReference.Latest)

        assertEquals(newest.id, deleted)
        assertEquals(listOf("session-test-1"), gateway.saved.map { it.id.value })
        assertThrows<SessionNotFoundException> { delete.execute(workspace, SessionReference.ById(newest.id)) }
    }
}
