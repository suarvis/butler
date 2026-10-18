package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.exception.DomainValidationException
import com.suarvis.butler.core.domain.generic.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessionReferenceTest {
    @ParameterizedTest
    @ValueSource(strings = ["latest", "last", "recent", " LATEST "])
    fun `latest aliases resolve to the latest reference`(raw: String) {
        assertEquals(SessionReference.Latest, SessionReference.parse(raw))
    }

    @Test
    fun `anything else is an id`() {
        assertEquals(SessionReference.ById(SessionId("session-42")), SessionReference.parse("session-42"))
        assertThrows<DomainValidationException> { SessionReference.parse("../etc") }
    }
}
