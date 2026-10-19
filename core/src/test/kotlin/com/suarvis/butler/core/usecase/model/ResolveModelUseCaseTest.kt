package com.suarvis.butler.core.usecase.model

import com.suarvis.butler.core.domain.exception.UnknownModelException
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.fixtures.FixedModelCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolveModelUseCaseTest {
    private val useCase = ResolveModelUseCase(FixedModelCatalog())

    @Test
    fun `no model means the configured default`() {
        assertEquals(ModelId("claude-sonnet-4-6"), useCase.execute(null).id)
    }

    @Test
    fun `a model reports the provider that will serve it`() {
        val profile = useCase.execute(ModelId("ollama/llama3.2"))

        assertEquals(FixedModelCatalog.OLLAMA, profile.provider)
        assertEquals("llama3.2", profile.apiModelName)
        assertThrows<UnknownModelException> { useCase.execute(ModelId("mystery")) }
    }
}
