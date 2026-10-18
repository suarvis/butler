package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.generic.ToolCallId
import com.suarvis.butler.core.domain.generic.ToolName

/** One piece of a conversation message. Mirrors Clawcode's content blocks one-to-one. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /** Model reasoning. The provider signature must round-trip unchanged or the provider rejects the transcript. */
    data class Thinking(val thinking: String, val signature: String?) : ContentBlock

    data class ToolUse(val id: ToolCallId, val name: ToolName, val input: JsonText) : ContentBlock

    data class ToolResult(
        val toolUseId: ToolCallId,
        val toolName: ToolName,
        val output: String,
        val isError: Boolean,
    ) : ContentBlock
}
