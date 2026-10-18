package com.suarvis.butler.api.cli.mapper

import com.suarvis.butler.api.cli.dto.PromptResultOutput
import com.suarvis.butler.api.cli.dto.ToolResultOutput
import com.suarvis.butler.api.cli.dto.ToolUseOutput
import com.suarvis.butler.core.domain.generic.MessageRole
import com.suarvis.butler.core.domain.turn.TurnSummary
import java.math.BigDecimal
import java.math.RoundingMode

/** Domain turn results ↔ CLI output DTOs. */
object TurnCliMapper {
    fun toPromptResult(summary: TurnSummary): PromptResultOutput {
        val messages = summary.session.messages
        val turnMessages = messages.drop(messages.indexOfLast { it.role == MessageRole.USER } + 1)
        return PromptResultOutput(
            message = summary.finalText,
            model = summary.model.id.value,
            provider = summary.model.provider.name.lowercase(),
            sessionId = summary.session.id.value,
            iterations = summary.iterations,
            toolUses = summary.assistantMessages.flatMap { it.toolUses }.map { ToolUseOutput(it.id.value, it.name.value, it.input.value) },
            toolResults =
                turnMessages.flatMap { it.toolResults }
                    .map { ToolResultOutput(it.toolUseId.value, it.toolName.value, it.output, it.isError) },
            usage = SessionCliMapper.toUsage(summary.usage),
            estimatedCost = summary.estimatedCostUsd()?.let(::formatUsd),
            stopReason = summary.stopReason.name.lowercase(),
        )
    }

    /** Same shape as Clawcode's `format_usd`: a dollar sign and four decimals. */
    fun formatUsd(amount: BigDecimal): String = "$" + amount.setScale(4, RoundingMode.HALF_UP).toPlainString()
}
