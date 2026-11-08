package com.suarvis.butler.api.config

import com.suarvis.butler.api.cli.SystemCliConsole
import com.suarvis.butler.api.cli.console.JLineCliConsole
import com.suarvis.butler.api.cli.render.TerminalStyle.ColorMode
import com.suarvis.butler.api.config.CliProperties.LineEditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** Which console the CLI gets, and `butler.cli` bound from the shipped application.yml. No terminal is opened. */
class CliConfigTest {
    @Test
    fun `piped input always gets the plain reader, whatever the setting`() {
        LineEditor.entries.forEach { lineEditor ->
            assertInstanceOf(SystemCliConsole::class.java, CliConfig.console(lineEditor, interactive = false), lineEditor.name)
        }
    }

    @Test
    fun `at a terminal auto and jline get JLine, and plain keeps the plain reader`() {
        assertInstanceOf(JLineCliConsole::class.java, CliConfig.console(LineEditor.AUTO, interactive = true))
        assertInstanceOf(JLineCliConsole::class.java, CliConfig.console(LineEditor.JLINE, interactive = true))
        assertInstanceOf(SystemCliConsole::class.java, CliConfig.console(LineEditor.PLAIN, interactive = true))
    }

    @Test
    fun `the shipped configuration chooses automatically`() {
        assertEquals(CliProperties(LineEditor.AUTO, ColorMode.AUTO), bind())
    }

    @Test
    fun `environment variables override both settings`() {
        assertEquals(CliProperties(LineEditor.PLAIN, ColorMode.NEVER), bind("BUTLER_CLI_LINEEDITOR" to "plain", "BUTLER_CLI_COLOR" to "never"))
        assertEquals(CliProperties(LineEditor.JLINE, ColorMode.ALWAYS), bind("BUTLER_CLI_LINEEDITOR" to "jline", "BUTLER_CLI_COLOR" to "always"))
    }

    private fun bind(vararg environmentVariables: Pair<String, Any>): CliProperties {
        val environment = StandardEnvironment()
        // A stand-in under the real source's name replaces it: a BUTLER_CLI_* in the developer's shell cannot
        // change the result, and Spring still maps BUTLER_CLI_LINEEDITOR to line-editor, which it does only
        // for the source named systemEnvironment.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*environmentVariables)),
        )
        return Binder.get(environment).bind("butler.cli", CliProperties::class.java).orElseGet { CliProperties() }
    }
}
