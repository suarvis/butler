package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.SessionNotFoundException
import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import com.suarvis.butler.core.fixtures.InMemorySessionGateway
import com.suarvis.butler.core.fixtures.MutableClock
import com.suarvis.butler.core.fixtures.ScriptedModelGateway
import com.suarvis.butler.core.fixtures.SequentialIdentityGateway
import com.suarvis.butler.core.fixtures.SessionFixtures
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class RunTurnUseCaseTest {
    private val sessions = InMemorySessionGateway()
    private val clock = MutableClock(SessionFixtures.start)
    private val workspace = SessionFixtures.workspace

    private fun useCase(models: ScriptedModelGateway) =
        RunTurnUseCase(GetSessionUseCase(sessions), sessions, SequentialIdentityGateway(), models, FixedModelCatalog(), clock)

    @Test
    fun `a first prompt starts a session and records prompt, answer and usage`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("Hello", " there"))

        val summary = useCase(models).execute(RunTurnUseCase.Command(workspace, "Say hello"))

        val saved = sessions.findById(workspace, summary.session.id)!!
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), saved.messages.map { it.role })
        assertEquals("Hello there", summary.finalText)
        assertEquals(TokenUsage(inputTokens = 10, outputTokens = 5), saved.messages.last().usage)
        assertEquals(ModelId("sonnet"), saved.model)
        assertEquals(1, summary.iterations)
    }

    @Test
    fun `the model receives the resolved model, a system prompt and the transcript`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("ok"))

        useCase(models).execute(RunTurnUseCase.Command(workspace, "Explain", model = ModelId("opus")))

        val request = models.requests.single()
        assertEquals(ModelId("claude-opus-4-7"), request.model.id)
        assertEquals(ProviderKind.ANTHROPIC, request.model.provider)
        assertTrue(request.systemPrompt.contains(workspace.toString()), request.systemPrompt)
        assertEquals(listOf(ConversationMessage.userText("Explain")), request.messages)
    }

    @Test
    fun `observers see start, deltas and completion in order`() {
        val events = mutableListOf<TurnEvent>()

        useCase(ScriptedModelGateway(ScriptedModelGateway.answer("a", "b"))).execute(
            RunTurnUseCase.Command(workspace, "go"),
            TurnObserver { events += it },
        )

        assertTrue(events.first() is TurnEvent.Started && (events.first() as TurnEvent.Started).newSession)
        assertEquals(listOf(TurnEvent.TextDelta("a"), TurnEvent.TextDelta("b")), events.subList(1, 3))
        assertTrue(events.last() is TurnEvent.Completed)
    }

    @Test
    fun `continuing a session sends the whole transcript and keeps its model`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("first"), ScriptedModelGateway.answer("second"))
        val turns = useCase(models)
        val first = turns.execute(RunTurnUseCase.Command(workspace, "one", model = ModelId("haiku")))

        val second = turns.execute(RunTurnUseCase.Command(workspace, "two", session = SessionReference.ById(first.session.id)))

        assertEquals(first.session.id, second.session.id)
        assertEquals(3, models.requests.last().messages.size)
        assertEquals(ModelId("claude-haiku-4-5"), models.requests.last().model.id)
        assertEquals(4, second.session.messageCount)
    }

    @Test
    fun `an explicit model switches the session's model`() {
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("x"), ScriptedModelGateway.answer("y"))
        val turns = useCase(models)
        val first = turns.execute(RunTurnUseCase.Command(workspace, "one"))

        val second = turns.execute(RunTurnUseCase.Command(workspace, "two", SessionReference.Latest, ModelId("opus")))

        assertEquals(ModelId("opus"), second.session.model)
        assertEquals(ModelId("sonnet"), first.session.model)
    }

    @Test
    fun `dangling tool calls from a crashed turn are closed before the new prompt`() {
        val call = ContentBlock.ToolUse(ToolCallId("toolu_9"), ToolName("bash"), JsonText("{}"))
        sessions.save(
            SessionFixtures.emptySession("session-crashed")
                .appendUserPrompt("run it", SessionFixtures.start)
                .appendAssistantMessage(ConversationMessage.assistant(listOf(call)), SessionFixtures.start),
        )
        val models = ScriptedModelGateway(ScriptedModelGateway.answer("recovered"))

        val summary =
            useCase(models).execute(RunTurnUseCase.Command(workspace, "again", SessionReference.ById(SessionId("session-crashed"))))

        val roles = summary.session.messages.map { it.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.USER, MessageRole.ASSISTANT), roles)
        assertTrue(summary.session.messages[2].toolResults.single().isError)
    }

    @Test
    fun `a provider failure keeps the prompt and propagates the error`() {
        val failure = ModelProviderException(ProviderKind.ANTHROPIC, ModelProviderException.Failure.RATE_LIMITED, "slow down", 30)
        val models = ScriptedModelGateway(ScriptedModelGateway.Reply.Failure(failure))

        val thrown = assertThrows<ModelProviderException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "keep me")) }

        assertEquals(30, thrown.retryAfterSeconds)
        val saved = sessions.findLatest(workspace)!!
        assertEquals(listOf("keep me"), saved.messages.map { it.text })
    }

    @Test
    fun `continuing a missing session fails before calling the model`() {
        val models = ScriptedModelGateway()

        assertThrows<SessionNotFoundException> { useCase(models).execute(RunTurnUseCase.Command(workspace, "x", SessionReference.Latest)) }
        assertTrue(models.requests.isEmpty())
    }

    @Test
    fun `cost is estimated from the model's pricing`() {
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 100_000)
        val summary = useCase(ScriptedModelGateway(ScriptedModelGateway.answer("x", usage = usage))).execute(RunTurnUseCase.Command(workspace, "go"))

        assertEquals(0, BigDecimal("4.5").compareTo(summary.estimatedCostUsd()))
    }
}
