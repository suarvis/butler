package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway
import com.suarvis.butler.secondary.gateway.impl.LocalWorkspaceGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.ToolInputGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * File tools: decoding tool input and running read, write, edit, glob and grep in the workspace.
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceConfig {
    @Bean
    fun toolInputGateway(): ToolInputGateway = ToolInputGatewayImpl()

    @Bean
    fun workspaceGateway(): WorkspaceGateway = LocalWorkspaceGatewayImpl()
}
