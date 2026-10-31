package com.suarvis.butler.api.cli.render

/**
 * Whether terminal output is coloured with ANSI escape codes. Colour reaches only a person at a
 * terminal who has not set `NO_COLOR`, so piped output, scripts and tests stay plain. Lane I's I3
 * adds the `butler.cli.color` setting on top of this.
 */
class TerminalStyle private constructor(val color: Boolean) {
    /**
     * [text] in the SGR style [code] (such as `1;32`). Each line is reset on its own, so a colour
     * never leaks past the end of a line, whoever prints the next one.
     */
    fun paint(text: String, code: String): String =
        if (!color) text else text.split('\n').joinToString("\n") { "\u001b[${code}m$it\u001b[0m" }

    companion object {
        val PLAIN = TerminalStyle(color = false)
        val COLOR = TerminalStyle(color = true)

        /** @param noColor the `NO_COLOR` environment variable; any non-empty value turns colour off */
        fun detect(interactive: Boolean, noColor: String?): TerminalStyle = if (interactive && noColor.isNullOrEmpty()) COLOR else PLAIN
    }
}
