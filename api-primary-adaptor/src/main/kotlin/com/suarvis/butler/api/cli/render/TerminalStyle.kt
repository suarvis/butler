package com.suarvis.butler.api.cli.render

/**
 * Whether terminal output is coloured with ANSI escape codes. By default colour reaches only a
 * person at a terminal who has not set `NO_COLOR`, so piped output, scripts and tests stay plain;
 * `butler.cli.color` can force it on or off.
 */
class TerminalStyle private constructor(val color: Boolean) {
    /** `butler.cli.color`. */
    enum class ColorMode {
        /** Colour at a terminal unless `NO_COLOR` is set. */
        AUTO,

        /** Colour even when piped or with `NO_COLOR` set. */
        ALWAYS,

        NEVER,
    }

    /**
     * [text] in the SGR style [code] (such as `1;32`). Each line is reset on its own, so a colour
     * never leaks past the end of a line, whoever prints the next one.
     */
    fun paint(text: String, code: String): String =
        if (!color) text else text.split('\n').joinToString("\n") { "\u001b[${code}m$it\u001b[0m" }

    companion object {
        val PLAIN = TerminalStyle(color = false)
        val COLOR = TerminalStyle(color = true)

        /** @param noColor the `NO_COLOR` environment variable; any non-empty value turns colour off in [ColorMode.AUTO] */
        fun detect(interactive: Boolean, noColor: String?, mode: ColorMode = ColorMode.AUTO): TerminalStyle =
            when (mode) {
                ColorMode.ALWAYS -> COLOR
                ColorMode.NEVER -> PLAIN
                ColorMode.AUTO -> if (interactive && noColor.isNullOrEmpty()) COLOR else PLAIN
            }
    }
}
