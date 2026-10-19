package com.suarvis.butler.secondary.gateway.impl

import com.suarvis.butler.core.domain.exception.ModelProviderException
import com.suarvis.butler.core.domain.exception.ModelProviderException.Failure
import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.generic.ProviderId
import com.suarvis.butler.core.domain.generic.ProviderKind
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelProvider
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.session.ContentBlock
import com.suarvis.butler.core.domain.session.ConversationMessage
import com.suarvis.butler.core.fixtures.ScriptedModelGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RoutingModelGatewayImplTest {
    private val anthropic = ScriptedModelGateway(ScriptedModelGateway.answer("from anthropic"))
    private val openai = ScriptedModelGateway(ScriptedModelGateway.answer("from openai"))
    private val router = RoutingModelGatewayImpl(mapOf(ProviderId("anthropic") to anthropic, ProviderId("openai") to openai))

    private fun request(provider: String) =
        ModelRequest(
            ModelProfile(ModelId("m"), ModelProvider(ProviderId(provider), ProviderKind.OPENAI_COMPATIBLE), null, null),
            "s",
            listOf(ConversationMessage.userText("hi")),
        )

    @Test
    fun `each request goes to the gateway of its model's provider`() {
        val response = router.stream(request("openai")) { }

        assertEquals("from openai", (response.blocks.single() as ContentBlock.Text).text)
        assertEquals(1, openai.requests.size)
        assertTrue(anthropic.requests.isEmpty())
    }

    @Test
    fun `a provider without configuration is reported as not configured`() {
        val error = assertThrows<ModelProviderException> { router.stream(request("xai")) { } }

        assertEquals(Failure.NOT_CONFIGURED, error.failure)
        assertEquals(ProviderId("xai"), error.provider)
    }
}
