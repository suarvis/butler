package com.suarvis.butler.api.config

import com.suarvis.butler.api.cli.render.TerminalStyle
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `butler.cli.*`: how the terminal looks and reads, display choices only. Each value has an
 * environment override, `BUTLER_CLI_LINEEDITOR` and `BUTLER_CLI_COLOR`.
 *
 * @param lineEditor `auto` and `jline` use JLine when a person is at a terminal; `plain` always
 *   uses the plain line reader, the escape hatch if JLine misbehaves in some terminal. Piped input
 *   always gets the plain reader.
 * @param color `auto` colours output at a terminal unless `NO_COLOR` is set; `always` or `never`.
 */
@ConfigurationProperties(prefix = "butler.cli")
data class CliProperties(
    val lineEditor: LineEditor = LineEditor.AUTO,
    val color: TerminalStyle.ColorMode = TerminalStyle.ColorMode.AUTO,
) {
    enum class LineEditor {
        AUTO,
        JLINE,
        PLAIN,
    }
}
