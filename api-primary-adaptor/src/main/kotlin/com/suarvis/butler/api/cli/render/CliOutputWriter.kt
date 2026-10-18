package com.suarvis.butler.api.cli.render

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.OutputFormat
import com.suarvis.butler.api.cli.dto.ErrorOutput
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.springframework.stereotype.Component

/**
 * Writes command results. Text goes to stdout; JSON goes to stdout as exactly one object per
 * result. Errors go to stderr in text mode but to stdout in JSON mode, so a script can always
 * parse stdout from byte 0 (Clawcode's contract).
 */
@Component
class CliOutputWriter(private val console: CliConsole) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

    fun <T> success(format: OutputFormat, serializer: KSerializer<T>, payload: T, text: () -> String) {
        val rendered =
            when (format) {
                OutputFormat.TEXT -> text()
                OutputFormat.JSON -> json.encodeToString(serializer, payload)
            }
        console.out.println(rendered)
        console.out.flush()
    }

    fun error(format: OutputFormat, payload: ErrorOutput, text: String) {
        when (format) {
            OutputFormat.JSON -> {
                console.out.println(json.encodeToString(ErrorOutput.serializer(), payload))
                console.out.flush()
            }
            OutputFormat.TEXT -> {
                console.err.println(text)
                console.err.flush()
            }
        }
    }
}
