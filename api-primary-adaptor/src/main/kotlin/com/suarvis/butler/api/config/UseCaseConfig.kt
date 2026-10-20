package com.suarvis.butler.api.config

import com.suarvis.butler.core.gateway.BudgetGateway
import com.suarvis.butler.core.gateway.IdentityGateway
import com.suarvis.butler.core.gateway.ModelCatalogGateway
import com.suarvis.butler.core.gateway.ModelGateway
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.core.usecase.budget.GetUsageReportUseCase
import com.suarvis.butler.core.usecase.model.ResolveModelUseCase
import com.suarvis.butler.core.usecase.session.DeleteSessionUseCase
import com.suarvis.butler.core.usecase.session.ForkSessionUseCase
import com.suarvis.butler.core.usecase.session.GetSessionUseCase
import com.suarvis.butler.core.usecase.session.ListSessionsUseCase
import com.suarvis.butler.core.usecase.session.StartSessionUseCase
import com.suarvis.butler.core.usecase.turn.RunTurnUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Core use cases are plain classes with no annotations; this is where they become beans.
 * Gateways are injected by interface; their implementations come from secondary-adaptor.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {
    /** System time zone: budgets reset at the start of the user's own calendar month. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

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

    @Bean
    fun resolveModelUseCase(catalog: ModelCatalogGateway) = ResolveModelUseCase(catalog)

    @Bean
    fun runTurnUseCase(
        getSession: GetSessionUseCase,
        sessions: SessionGateway,
        identity: IdentityGateway,
        models: ModelGateway,
        catalog: ModelCatalogGateway,
        usage: UsageGateway,
        rateLimits: RateLimitGateway,
        budgets: BudgetGateway,
        clock: Clock,
    ) = RunTurnUseCase(getSession, sessions, identity, models, catalog, usage, rateLimits, budgets, clock)

    @Bean
    fun getUsageReportUseCase(
        usage: UsageGateway,
        budgets: BudgetGateway,
        clock: Clock,
    ) = GetUsageReportUseCase(usage, budgets, clock)
}
