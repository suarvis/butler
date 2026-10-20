package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.gateway.impl.FileRateLimitGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.FileSessionGatewayImpl
import com.suarvis.butler.secondary.gateway.impl.FileUsageGatewayImpl
import com.suarvis.butler.secondary.persistence.file.repository.RateLimitFileRepository
import com.suarvis.butler.secondary.persistence.file.repository.SessionFileRepository
import com.suarvis.butler.secondary.persistence.file.repository.UsageFileRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * Default store: Clawcode-compatible JSONL session files inside each workspace, plus the usage
 * ledger and rate-limit cooldowns in the user's `~/.butler`. Needs no infrastructure.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "butler.persistence", name = ["store"], havingValue = "file", matchIfMissing = true)
class FilePersistenceConfig {
    @Bean
    fun sessionFileRepository(properties: PersistenceProperties): SessionFileRepository =
        SessionFileRepository(properties.file.directory)

    @Bean
    fun sessionGateway(repository: SessionFileRepository): SessionGateway = FileSessionGatewayImpl(repository)

    @Bean
    fun usageGateway(properties: PersistenceProperties): UsageGateway =
        FileUsageGatewayImpl(UsageFileRepository(Path.of(properties.file.userDirectory)))

    @Bean
    fun rateLimitGateway(properties: PersistenceProperties): RateLimitGateway =
        FileRateLimitGatewayImpl(RateLimitFileRepository(Path.of(properties.file.userDirectory)))
}
