package com.suarvis.butler.api.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ClassPathResource

/** Binds `butler.permissions.default-mode` (D2b) from the shipped application.yml, as the application does at start-up. */
class PermissionModePropertiesTest {
    @Test
    fun `the shipped configuration starts runs in prompt`() {
        assertEquals("prompt", bind().defaultMode)
    }

    @Test
    fun `the environment variable picks another mode`() {
        assertEquals("workspace-write", bind("BUTLER_PERMISSIONS_DEFAULTMODE" to "workspace-write").defaultMode)
    }

    private fun bind(vararg environmentVariables: Pair<String, Any>): PermissionModeProperties {
        val environment = StandardEnvironment()
        // A stand-in under the real source's name replaces it: the developer's shell cannot change the
        // result, and Spring still maps BUTLER_PERMISSIONS_DEFAULTMODE to default-mode, which it does only
        // for the source named systemEnvironment.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*environmentVariables)),
        )
        return Binder.get(environment).bind("butler.permissions", PermissionModeProperties::class.java).orElseGet { PermissionModeProperties() }
    }
}
