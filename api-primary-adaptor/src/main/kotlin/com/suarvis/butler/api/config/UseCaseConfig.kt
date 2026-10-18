package com.suarvis.butler.api.config

import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.usecase.session.DeleteSessionUseCase
import com.suarvis.butler.core.usecase.session.ForkSessionUseCase
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import com.suarvis.butler.core.usecase.session.ListSessionsUseCase
import com.suarvis.butler.core.usecase.session.StartSessionUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Core use cases are plain classes with no annotations; this is where they become beans.
 * Gateways are injected by interface; their implementations come from secondary-adaptor.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun startSessionUseCase(sessions: SessionGateway, identity: IdentityGateway, clock: Clock) =
        StartSessionUseCase(sessions, identity, clock)

    @Bean
    fun getSessionUseCase(sessions: SessionGateway) = GetSessionUseCase(sessions)

    @Bean
    fun listSessionsUseCase(sessions: SessionGateway) = ListSessionsUseCase(sessions)

    @Bean
    fun forkSessionUseCase(
        getSession: GetSessionUseCase,
        sessions: SessionGateway,
        identity: IdentityGateway,
        clock: Clock,
    ) = ForkSessionUseCase(getSession, sessions, identity, clock)

    @Bean
    fun deleteSessionUseCase(getSession: GetSessionUseCase, sessions: SessionGateway) =
        DeleteSessionUseCase(getSession, sessions)
}
