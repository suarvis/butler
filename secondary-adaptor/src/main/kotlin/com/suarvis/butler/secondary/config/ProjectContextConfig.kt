package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.ProjectContextGateway
import com.suarvis.butler.secondary.gateway.impl.stub.NoProjectContextGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Instruction files and git state for the system prompt. Empty for now; lane G swaps in the real reader here. */
@Configuration(proxyBeanMethods = false)
class ProjectContextConfig {
    @Bean
    fun projectContextGateway(): ProjectContextGateway = NoProjectContextGatewayImpl()
}
