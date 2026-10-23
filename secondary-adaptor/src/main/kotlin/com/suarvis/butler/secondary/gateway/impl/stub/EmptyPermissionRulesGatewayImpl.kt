package com.suarvis.butler.secondary.gateway.impl.stub

import com.suarvis.butler.core.domain.permission.PermissionRules
import com.suarvis.butler.core.gateway.PermissionRulesGateway

/**
 * Stand-in until lane D's PermissionRulesGatewayImpl replaces it with rules from
 * `butler.permissions.*`. No rules leaves every decision to the permission mode.
 */
class EmptyPermissionRulesGatewayImpl : PermissionRulesGateway {
    override fun rules(): PermissionRules = PermissionRules.NONE
}
