package com.suarvis.butler.secondary.config

import com.suarvis.butler.core.gateway.SessionGateway
import com.suarvis.butler.secondary.gateway.impl.FileSessionGatewayImpl
import com.suarvis.butler.secondary.persistence.file.repository.SessionFileRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Default store: Clawcode-compatible JSONL files inside each workspace. Needs no infrastructure. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "butler.persistence", name = ["store"], havingValue = "file", matchIfMissing = true)
class FilePersistenceConfig {
    @Bean
    fun sessionFileRepository(properties: PersistenceProperties): SessionFileRepository =
        SessionFileRepository(properties.file.directory)

    @Bean
    fun sessionGateway(repository: SessionFileRepository): SessionGateway = FileSessionGatewayImpl(repository)
}
