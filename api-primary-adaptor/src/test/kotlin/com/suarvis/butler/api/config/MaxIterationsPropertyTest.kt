package com.suarvis.butler.api.config

import com.suarvis.butler.core.domain.turn.TurnSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** Binds `butler.tools.max-iterations` (A3) from the shipped application.yml, as the application does at start-up. */
class MaxIterationsPropertyTest {
    @Test
    fun `a turn may make 32 model calls in the shipped configuration`() {
        assertEquals(TurnSettings.DEFAULT_MAX_ITERATIONS, bind().maxIterations)
        assertEquals(32, bind().maxIterations)
    }

    @Test
    fun `the environment variable changes the limit`() {
        assertEquals(5, bind("BUTLER_TOOLS_MAXITERATIONS" to "5").maxIterations)
    }

    private fun bind(vararg environmentVariables: Pair<String, Any>): TurnProperties {
        val environment = StandardEnvironment()
        // A stand-in under the real source's name replaces it, so the developer's shell cannot change the
        // result while Spring still applies its environment-variable naming (dashes dropped).
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*environmentVariables)),
        )
        return Binder.get(environment).bind("butler.tools", TurnProperties::class.java).orElseGet { TurnProperties() }
    }
}
