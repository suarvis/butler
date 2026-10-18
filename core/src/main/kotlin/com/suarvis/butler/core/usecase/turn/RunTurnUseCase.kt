package com.suarvis.butler.core.usecase.turn

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelStreamEvent
import com.suarvis.butler.core.domain.session.Session
import com.suarvis.butler.core.domain.session.SessionReference
import com.suarvis.butler.core.domain.turn.SystemPromptComposer
import com.suarvis.butler.core.domain.turn.TurnEvent
import com.suarvis.butler.core.domain.turn.TurnObserver
import com.suarvis.butler.core.domain.turn.TurnSummary
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import java.time.Clock
import java.time.LocalDate

/**
 * One user turn: record the prompt, ask the model, record the answer.
 *
 * The prompt is saved before the model is called, as Clawcode does, so a failed or interrupted
 * call never loses what the user typed. Tool execution and the model ↔ tool loop arrive in the
 * tools slice; until then the model is not offered any tools.
 */
class RunTurnUseCase(
    private val getSession: GetSessionUseCase,
    private val sessions: SessionGateway,
    private val identity: IdentityGateway,
    private val models: ModelGateway,
    private val catalog: ModelCatalogGateway,
    private val clock: Clock,
) {
    /**
     * @param session continue this saved session; null starts a new one.
     * @param model override for this turn; otherwise the session's model, otherwise the default.
     */
    data class Command(
        val workspace: WorkspaceRoot,
        val prompt: String,
        val session: SessionReference? = null,
        val model: ModelId? = null,
    )

    fun execute(command: Command, observer: TurnObserver = TurnObserver.NONE): TurnSummary {
        val startedAt = clock.instant()
        val existing = command.session?.let { getSession.execute(command.workspace, it) }
        var session =
            (existing ?: Session.start(identity.nextSessionId(), command.workspace, null, startedAt))
                .closeDanglingToolCalls(INTERRUPTED_TOOL_RESULT, startedAt)

        val modelId = command.model ?: session.model ?: catalog.defaultModel()
        val profile = catalog.resolve(modelId)
        if (session.model != modelId) session = session.switchModel(modelId, startedAt)

        session = session.appendUserPrompt(command.prompt, startedAt)
        sessions.save(session)
        observer.onEvent(TurnEvent.Started(session.id, modelId, newSession = existing == null))

        val systemPrompt = SystemPromptComposer.compose(command.workspace, LocalDate.ofInstant(startedAt, clock.zone), profile.id)
        val response =
            models.stream(ModelRequest(profile, systemPrompt, session.messages)) { event ->
                observer.onEvent(
                    when (event) {
                        is ModelStreamEvent.TextDelta -> TurnEvent.TextDelta(event.text)
                        is ModelStreamEvent.ThinkingDelta -> TurnEvent.ThinkingDelta(event.text)
                    },
                )
            }

        val answer = response.toAssistantMessage()
        val answeredAt = clock.instant()
        session =
            session.appendAssistantMessage(answer, answeredAt)
                // No tools are offered yet; answering any stray call keeps the transcript valid.
                .closeDanglingToolCalls(TOOLS_UNAVAILABLE_RESULT, answeredAt)
        sessions.save(session)

        val summary = TurnSummary(session, profile, listOf(answer), iterations = 1, usage = response.usage, stopReason = response.stopReason)
        observer.onEvent(TurnEvent.Completed(summary))
        return summary
    }

    private companion object {
        const val INTERRUPTED_TOOL_RESULT = "Interrupted before this tool call completed"
        const val TOOLS_UNAVAILABLE_RESULT = "Tools are not available in this version of Butler"
    }
}
