package com.suarvis.butler.api.config

import com.suarvis.butler.api.cli.CliConsole
import com.suarvis.butler.api.cli.SystemCliConsole
import com.suarvis.butler.api.cli.console.JLineCliConsole
import com.suarvis.butler.api.cli.console.JvmInterruptSignals
import com.suarvis.butler.api.cli.repl.InterruptSignals
import com.suarvis.butler.api.cli.repl.TurnInterrupts
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The terminal side of the CLI: which console reads and writes, and where Ctrl-C during a turn comes from. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CliProperties::class)
class CliConfig {
    /** Closed with the context, so a JLine terminal gets its modes back before the process ends. */
    @Bean
    fun cliConsole(properties: CliProperties): CliConsole = console(properties.lineEditor, SystemCliConsole.detectInteractive())

    /**
     * The JLine console is its own signal source, since it also sees Ctrl-C typed at an approval
     * prompt. Not a bean of its own: the console bean already is an [InterruptSignals] then, and two
     * candidates would make the injection ambiguous.
     */
    @Bean
    fun turnInterrupts(console: CliConsole): TurnInterrupts = TurnInterrupts(console, console as? InterruptSignals ?: JvmInterruptSignals)

    companion object {
        /** JLine only for a person at a terminal; piped input and every script keep the plain reader. */
        fun console(lineEditor: CliProperties.LineEditor, interactive: Boolean): CliConsole =
            when {
                !interactive || lineEditor == CliProperties.LineEditor.PLAIN -> SystemCliConsole()
                else -> JLineCliConsole()
            }
    }
}
