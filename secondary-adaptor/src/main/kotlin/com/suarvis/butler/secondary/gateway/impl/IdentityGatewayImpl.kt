package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.generic.SessionId
import com.suarvis.butler.core.gateway.IdentityGateway
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** Same id shape as Clawcode (`session-<epochMillis>-<sequence>`), so file sessions stay interchangeable. */
@Component
class IdentityGatewayImpl(private val clock: Clock) : IdentityGateway {
    private val sequence = AtomicLong()

    override fun nextSessionId(): SessionId = SessionId("session-${clock.millis()}-${sequence.getAndIncrement()}")
}
