package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.domain.permission.ToolPattern
import com.suarvis.butler.core.domain.permission.ToolPatterns
import com.suarvis.butler.core.gateway.PermissionRulesGateway
import com.suarvis.butler.secondary.config.PermissionProperties

/**
 * Permission rules from `butler.permissions.*`, parsed once when the gateway is built. A rule that
 * does not parse stops the run there, naming the rule and where it was configured, instead of being
 * skipped: a mistyped deny rule that silently denied nothing would be worse than not starting.
 */
class PermissionRulesGatewayImpl(properties: PermissionProperties) : PermissionRulesGateway {
    private val rules =
        PermissionRules(
            allow = parse("allow", properties.allow),
            deny = parse("deny", properties.deny),
            ask = parse("ask", properties.ask),
        )

    override fun rules(): PermissionRules = rules

    private fun parse(list: String, raw: List<String>): List<ToolPattern> =
        raw.mapIndexed { index, rule ->
            try {
                ToolPatterns.parse(rule)
            } catch (invalid: DomainValidationException) {
                throw DomainValidationException("Invalid permission rule '$rule' in butler.permissions.$list[$index]")
            }
        }
}
