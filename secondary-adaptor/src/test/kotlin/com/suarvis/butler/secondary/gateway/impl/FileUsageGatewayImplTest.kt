package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.UsageGateway
import com.suarvis.butler.secondary.persistence.file.repository.RateLimitFileRepository
import com.suarvis.butler.secondary.persistence.file.repository.UsageFileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileUsageGatewayImplTest : UsageGatewayContract() {
    @TempDir
    lateinit var home: Path

    override fun gateway(): UsageGateway = FileUsageGatewayImpl(UsageFileRepository(home))

    @Test
    fun `the ledger is one JSON line per call in a file per month, with exact decimal cost`() {
        gateway().record(
            UsageRecord(Instant.parse("2026-10-05T10:00:00Z"), ProviderId("openai"), ModelId("gpt-5.5"), TokenUsage(inputTokens = 7), BigDecimal("0.0000350")),
        )

        val line = home.resolve("usage/2026-10.jsonl").readText().trim()
        assertTrue(line.contains("\"provider\":\"openai\""), line)
        assertTrue(line.contains("\"cost_usd\":\"0.0000350\""), line)
    }

    @Test
    fun `a line cut short by a crash is skipped`() {
        val gateway = gateway()
        gateway.record(UsageRecord(Instant.parse("2026-10-05T10:00:00Z"), ProviderId("openai"), ModelId("gpt-5.5"), TokenUsage(inputTokens = 7), BigDecimal("1")))
        home.resolve("usage/2026-10.jsonl").appendText("{\"at_ms\":17")

        val summary = gateway.summarize(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))

        assertEquals(1, summary.totals.single().calls)
    }
}

class FileRateLimitGatewayImplTest : RateLimitGatewayContract() {
    @TempDir
    lateinit var home: Path

    override fun gateway(): RateLimitGateway = FileRateLimitGatewayImpl(RateLimitFileRepository(home))

    @Test
    fun `a damaged file reads as no cooldowns`() {
        Files.createDirectories(home)
        home.resolve("rate-limits.json").writeText("not json")

        assertTrue(gateway().activeCooldowns(Instant.parse("2026-10-17T09:00:00Z")).isEmpty())
    }
}
