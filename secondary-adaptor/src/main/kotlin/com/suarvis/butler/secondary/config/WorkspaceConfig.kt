package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.ToolInputGateway
import com.suarvis.butler.core.gateway.WorkspaceGateway
import com.suarvis.butler.secondary.gateway.impl.stub.UnavailableToolInputGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.stub.UnavailableWorkspaceGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * File tools: decoding tool input and running read, write, edit, glob and grep in the workspace.
 * Stand-ins for now; lane C swaps in the real implementations here.
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceConfig {
    @Bean
    fun toolInputGateway(): ToolInputGateway = UnavailableToolInputGatewayImpl()

    @Bean
    fun workspaceGateway(): WorkspaceGateway = UnavailableWorkspaceGatewayImpl()
}
