package com.suarvis.butler.api.cli.action

import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.dto.UsageReportOutput
import com.suarvis.butler.api.cli.mapper.UsageCliMapper
import com.suarvis.butler.api.cli.render.CliOutputWriter
import com.suarvis.butler.api.cli.render.UsageTextRenderer
import com.suarvis.butler.core.usecase.budget.GetUsageReportUseCase
import org.springframework.stereotype.Component

/** `butler usage` and `/usage`. */
@Component
class UsageActions(
    private val getUsageReport: GetUsageReportUseCase,
    private val output: CliOutputWriter,
) {
    fun report(format: OutputFormat) {
        val payload = UsageCliMapper.toOutput(getUsageReport.execute())
        output.success(format, UsageReportOutput.serializer(), payload) { UsageTextRenderer.report(payload) }
    }
}
