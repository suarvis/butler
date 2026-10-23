package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.PermissionRulesGateway
import com.suarvis.butler.secondary.gateway.impl.stub.EmptyPermissionRulesGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Permission rules for tool calls. Until lane D reads them from `butler.permissions.*`, there are
 * none, so the permission mode alone decides.
 */
@Configuration(proxyBeanMethods = false)
class PermissionConfig {
    @Bean
    fun permissionRulesGateway(): PermissionRulesGateway = EmptyPermissionRulesGatewayImpl()
}
