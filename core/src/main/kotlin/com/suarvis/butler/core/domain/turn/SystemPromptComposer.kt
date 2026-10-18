package com.suarvis.butler.core.domain.turn

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.WorkspaceRoot
import java.time.LocalDate

/**
 * Builds the system prompt. Project instructions (CLAUDE.md, AGENTS.md, ...) and memory are added
 * in a later slice through a gateway; this is the fixed part that describes the environment.
 */
object SystemPromptComposer {
    fun compose(workspace: WorkspaceRoot, today: LocalDate, model: ModelId): String =
        """
        You are Butler, a coding agent working with a developer in their terminal.
        Be direct and concise. Format answers as Markdown suitable for a terminal.
        You cannot read files or run commands in this version, so answer from the conversation and
        say so when you would need to look at the code.

        Environment
        - Working directory: $workspace
        - Today's date: $today
        - Model: $model
        """.trimIndent()
}
