package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.secondary.persistence.file.mapper.UsageFilePersistenceMapper
import com.suarvis.butler.secondary.persistence.file.repository.RateLimitFileRepository
import java.io.IOException
import java.time.Instant

/** Rate-limit cooldowns in `~/.butler/rate-limits.json`: one entry per provider, so the file never grows. */
class FileRateLimitGatewayImpl(private val repository: RateLimitFileRepository) : RateLimitGateway {
    override fun activeCooldowns(now: Instant): Map<ProviderId, Instant> =
        storageCall("read rate limits") { UsageFilePersistenceMapper.toCooldowns(repository.read()).filterValues { it.isAfter(now) } }

    override fun record(provider: ProviderId, until: Instant) {
        storageCall("record a rate limit") {
            repository.write(UsageFilePersistenceMapper.toRecord(UsageFilePersistenceMapper.toCooldowns(repository.read()) + (provider to until)))
        }
    }

    private inline fun <T> storageCall(action: String, block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw GatewayException("Could not $action: ${error.message}", error)
        }
}
