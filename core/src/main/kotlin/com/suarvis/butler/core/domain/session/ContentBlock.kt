package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName

/** One piece of a conversation message. Mirrors Clawcode's content blocks one-to-one. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /**
     * Model reasoning. The provider signature must round-trip unchanged or the provider rejects the
     * transcript. [replay] is stored beside it for providers that continue reasoning from their own
     * opaque data rather than from a signature; it is null for every block Clawcode writes.
     */
    data class Thinking(
        val thinking: String,
        val signature: String?,
        val replay: ReasoningReplay? = null,
    ) : ContentBlock

    data class ToolUse(val id: ToolCallId, val name: ToolName, val input: JsonText) : ContentBlock

    data class ToolResult(
        val toolUseId: ToolCallId,
        val toolName: ToolName,
        val output: String,
        val isError: Boolean,
    ) : ContentBlock
}

/**
 * Provider-bound data that lets the same provider continue its reasoning on the next call, such
 * as an OpenAI encrypted reasoning item. Only [provider] accepts it back, so the transcript
 * normalizer drops it when the session moves to another provider. [payload] is opaque.
 */
data class ReasoningReplay(val provider: ProviderId, val itemId: String?, val payload: String)
