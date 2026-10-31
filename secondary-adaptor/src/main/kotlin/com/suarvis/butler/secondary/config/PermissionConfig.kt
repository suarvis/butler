package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.PermissionRulesGateway
import com.suarvis.butler.secondary.gateway.impl.PermissionRulesGatewayImpl
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Permission rules for tool calls, read from `butler.permissions.*`. The properties are registered
 * here rather than in [SecondaryAdaptorConfig], so lane D's configuration stays in lane D's class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PermissionProperties::class)
class PermissionConfig {
    @Bean
    fun permissionRulesGateway(properties: PermissionProperties): PermissionRulesGateway = PermissionRulesGatewayImpl(properties)
}
