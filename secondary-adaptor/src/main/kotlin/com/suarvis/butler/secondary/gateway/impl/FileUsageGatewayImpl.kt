package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.budget.UsageSummary
import com.suarvis.butler.core.domain.exception.ButlerException
import com.suarvis.butler.core.domain.exception.GatewayException
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.persistence.file.mapper.UsageFilePersistenceMapper
import com.suarvis.butler.secondary.persistence.file.repository.UsageFileRepository
import java.io.IOException
import java.time.Instant

/** Usage ledger in `~/.butler/usage`, shared by every workspace of the user. */
class FileUsageGatewayImpl(private val repository: UsageFileRepository) : UsageGateway {
    override fun record(record: UsageRecord) {
        storageCall("record usage") { repository.append(UsageFilePersistenceMapper.toLine(record)) }
    }

    override fun summarize(from: Instant, to: Instant): UsageSummary =
        storageCall("read the usage ledger") { UsageSummary.of(repository.read(from, to).map(UsageFilePersistenceMapper::toDomain)) }

    private inline fun <T> storageCall(action: String, block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw GatewayException("Could not $action: ${error.message}", error)
        } catch (error: ButlerException) {
            throw GatewayException("Could not $action: stored data is invalid (${error.message})", error)
        }
}
