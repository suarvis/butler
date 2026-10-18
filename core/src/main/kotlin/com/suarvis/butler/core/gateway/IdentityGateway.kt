package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.generic.SessionId

/** Source of new identifiers; a port so tests can make ids deterministic. */
interface IdentityGateway {
    fun nextSessionId(): SessionId
}
