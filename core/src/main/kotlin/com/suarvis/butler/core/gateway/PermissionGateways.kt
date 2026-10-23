package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.permission.PermissionRules

/** The user's permission rules, read from configuration (`butler.permissions.*`), which core cannot read itself. */
interface PermissionRulesGateway {
    fun rules(): PermissionRules
}
