package com.suarvis.butler.core.fixtures

import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.gateway.IdentityGateway
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SequentialIdentityGateway(private val prefix: String = "session-test") : IdentityGateway {
    private var next = 1

    override fun nextSessionId(): SessionId = SessionId("$prefix-${next++}")
}

/** Clock that only moves when a test tells it to. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-17T09:00:00Z")) : Clock() {
    fun advance(duration: Duration): Instant {
        now = now.plus(duration)
        return now
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
