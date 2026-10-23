package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.CommandGateway
import com.suarvis.butler.secondary.gateway.impl.stub.UnavailableCommandGatewayImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The `bash` tool's shell. A stand-in for now; lane F swaps in the sandboxed shell here. */
@Configuration(proxyBeanMethods = false)
class CommandConfig {
    @Bean
    fun commandGateway(): CommandGateway = UnavailableCommandGatewayImpl()
}
