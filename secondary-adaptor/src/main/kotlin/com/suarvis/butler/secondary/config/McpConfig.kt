package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.McpGateway
import com.suarvis.butler.secondary.gateway.impl.stub.NoMcpGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** MCP servers. None for now; lane H swaps in the MCP client here. */
@Configuration(proxyBeanMethods = false)
class McpConfig {
    @Bean
    fun mcpGateway(): McpGateway = NoMcpGatewayImpl()
}
