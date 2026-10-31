package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.permission.ToolPattern
import com.suarvis.butler.secondary.config.PermissionProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ByteArrayResource

/** Binds `butler.permissions` from YAML and the environment, as the application does at start-up, then parses the rules. */
class PermissionRulesGatewayImplTest {
    private val bash = ToolName("bash")

    @Test
    fun `rules bind from YAML in Clawcode's syntax`() {
        val yaml =
            """
            butler:
              permissions:
                allow: ["bash(git status)", read_file]
                deny: ["bash(rm:*)"]
                ask: ["write_file(.github/:*)"]
            """.trimIndent()

        assertEquals(
            PermissionRules(
                allow = listOf(ToolPattern(bash, "git status"), ToolPattern(ToolName("read_file"))),
                deny = listOf(ToolPattern(bash, "rm:*")),
                ask = listOf(ToolPattern(ToolName("write_file"), ".github/:*")),
            ),
            gateway(yaml).rules(),
        )
    }

    @Test
    fun `indexed environment variables replace a YAML list and keep commas inside a rule`() {
        val yaml =
            """
            butler:
              permissions:
                deny: ["bash(rm:*)"]
            """.trimIndent()

        val rules =
            gateway(
                yaml,
                "BUTLER_PERMISSIONS_DENY_0" to "bash(git push:*)",
                "BUTLER_PERMISSIONS_ASK_0" to "bash(echo a,b)",
                "BUTLER_PERMISSIONS_ASK_1" to "edit_file",
            ).rules()

        assertEquals(listOf(ToolPattern(bash, "git push:*")), rules.deny)
        assertEquals(listOf(ToolPattern(bash, "echo a,b"), ToolPattern(ToolName("edit_file"))), rules.ask)
        assertEquals(emptyList<ToolPattern>(), rules.allow)
    }

    @Test
    fun `the shipped block with empty lists, or no block at all, gives no rules`() {
        val emptyLists =
            """
            butler:
              permissions:
                allow: []
                deny: []
                ask: []
            """.trimIndent()
        val noBlock =
            """
            butler:
              tools:
                enabled: false
            """.trimIndent()

        assertEquals(PermissionRules.NONE, gateway(emptyLists).rules())
        assertEquals(PermissionRules.NONE, gateway(noBlock).rules())
    }

    @Test
    fun `a rule that does not parse is refused, naming the rule and where it is configured`() {
        val allow = assertThrows<DomainValidationException> { PermissionRulesGatewayImpl(PermissionProperties(allow = listOf("read_file", "bash("))) }
        val deny = assertThrows<DomainValidationException> { PermissionRulesGatewayImpl(PermissionProperties(deny = listOf(" "))) }

        assertEquals("Invalid permission rule 'bash(' in butler.permissions.allow[1]", allow.message)
        assertEquals("Invalid permission rule ' ' in butler.permissions.deny[0]", deny.message)
    }

    private fun gateway(yaml: String, vararg environmentVariables: Pair<String, Any>): PermissionRulesGatewayImpl =
        PermissionRulesGatewayImpl(bind(yaml, *environmentVariables))

    private fun bind(yaml: String, vararg environmentVariables: Pair<String, Any>): PermissionProperties {
        val environment = StandardEnvironment()
        // The real environment stays out, so a BUTLER_PERMISSIONS_* in the developer's shell cannot change the result.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader()
            .load("application.yml", ByteArrayResource(yaml.toByteArray(Charsets.UTF_8)))
            .forEach(environment.propertySources::addLast)
        // Named as the real one, so Spring maps BUTLER_PERMISSIONS_DENY_0 to butler.permissions.deny[0] as it does at start-up.
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*environmentVariables)),
        )
        return Binder.get(environment).bind("butler.permissions", PermissionProperties::class.java).orElseGet { PermissionProperties() }
    }
}
