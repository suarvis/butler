package com.suarvis.butler.api.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** Binds `butler.tools` from the shipped application.yml, as the application does at start-up. */
class TurnPropertiesTest {
    @Test
    fun `tools are on in the shipped configuration, since gate M1`() {
        assertTrue(bind().enabled)
    }

    @Test
    fun `the environment variable switches tools off`() {
        assertFalse(bind("BUTLER_TOOLS_ENABLED" to "false").enabled)
    }

    private fun bind(vararg environmentVariables: Pair<String, Any>): TurnProperties {
        val environment = StandardEnvironment()
        // The real environment stays out, so a BUTLER_TOOLS_ENABLED in the developer's shell cannot change the result.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        environment.propertySources.addFirst(SystemEnvironmentPropertySource("test-environment", mapOf(*environmentVariables)))
        return Binder.get(environment).bind("butler.tools", TurnProperties::class.java).orElseGet { TurnProperties() }
    }
}
