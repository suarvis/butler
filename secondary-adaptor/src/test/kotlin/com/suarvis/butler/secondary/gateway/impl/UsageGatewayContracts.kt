package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.budget.Spend
import com.suarvis.butler.core.domain.budget.UsageRecord
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.TokenUsage
import com.suarvis.butler.core.gateway.RateLimitGateway
import com.suarvis.butler.core.gateway.UsageGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** Behaviour every [UsageGateway] must share, whatever stores the ledger. */
abstract class UsageGatewayContract {
    protected abstract fun gateway(): UsageGateway

    private val anthropic = ProviderId("anthropic")
    private val sonnet = ModelId("claude-sonnet-4-6")

    private fun record(at: String, usd: String?, input: Long = 1_000, provider: ProviderId = anthropic, model: ModelId = sonnet) =
        UsageRecord(Instant.parse(at), provider, model, TokenUsage(inputTokens = input, outputTokens = 10, cacheReadInputTokens = 5), usd?.let(::BigDecimal))

    @Test
    fun `usage in the window is summed per provider and model`() {
        val gateway = gateway()
        gateway.record(record("2026-10-01T00:00:00Z", "0.25"))
        gateway.record(record("2026-10-15T12:00:00Z", "0.50"))
        gateway.record(record("2026-10-16T12:00:00Z", "0.10", provider = ProviderId("openai"), model = ModelId("gpt-5.5")))

        val summary = gateway.summarize(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))

        val sonnetTotal = summary.totals.single { it.model == sonnet }
        assertEquals(2, sonnetTotal.calls)
        assertEquals(0, BigDecimal("0.75").compareTo(sonnetTotal.spend.usd))
        assertEquals(2 * 1_015L, sonnetTotal.spend.tokens)
        assertEquals(2, summary.totals.size)
    }

    @Test
    fun `the window includes its start and excludes its end, across month files`() {
        val gateway = gateway()
        gateway.record(record("2026-09-30T23:59:59Z", "1"))
        gateway.record(record("2026-10-31T18:30:00Z", "2"))
        gateway.record(record("2026-11-30T18:29:59Z", "4"))
        gateway.record(record("2026-11-30T18:30:00Z", "8"))

        // November in Asia/Colombo: 2026-10-31T18:30Z (inclusive) to 2026-11-30T18:30Z (exclusive).
        val summary = gateway.summarize(Instant.parse("2026-10-31T18:30:00Z"), Instant.parse("2026-11-30T18:30:00Z"))

        assertEquals(0, BigDecimal("6").compareTo(summary.overall.usd))
    }

    @Test
    fun `unpriced usage counts tokens but no money`() {
        val gateway = gateway()
        gateway.record(record("2026-10-05T00:00:00Z", null, provider = ProviderId("ollama"), model = ModelId("ollama/llama3.2")))

        val summary = gateway.summarize(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))

        assertEquals(0, BigDecimal.ZERO.compareTo(summary.overall.usd))
        assertEquals(1_015L, summary.overall.tokens)
    }

    @Test
    fun `an empty ledger summarizes to nothing`() {
        val summary = gateway().summarize(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))

        assertTrue(summary.totals.isEmpty())
        assertEquals(Spend.ZERO.tokens, summary.overall.tokens)
    }
}

/** Behaviour every [RateLimitGateway] must share. */
abstract class RateLimitGatewayContract {
    protected abstract fun gateway(): RateLimitGateway

    private val now = Instant.parse("2026-10-17T09:00:00Z")

    @Test
    fun `a cooldown is active until it ends`() {
        val gateway = gateway()
        gateway.record(ProviderId("anthropic"), now.plusSeconds(30))
        gateway.record(ProviderId("openai"), now.minusSeconds(1))

        assertEquals(mapOf(ProviderId("anthropic") to now.plusSeconds(30)), gateway.activeCooldowns(now))
        assertTrue(gateway.activeCooldowns(now.plusSeconds(30)).isEmpty())
    }

    @Test
    fun `recording again replaces a provider's cooldown`() {
        val gateway = gateway()
        gateway.record(ProviderId("anthropic"), now.plusSeconds(30))
        gateway.record(ProviderId("anthropic"), now.plusSeconds(90))

        assertEquals(now.plusSeconds(90), gateway.activeCooldowns(now)[ProviderId("anthropic")])
    }
}
