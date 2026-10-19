package com.suarvis.butler.core.gateway

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.domain.model.ModelRequest
import com.suarvis.butler.core.domain.model.ModelResponse
import com.suarvis.butler.core.domain.model.ModelStreamEvent

/**
 * Calls a language model. Implementations stream the response, report deltas through [onEvent]
 * as they arrive, and return the assembled response. Failures surface as
 * [com.suarvis.butler.core.domain.exception.ModelProviderException].
 */
interface ModelGateway {
    fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse
}

/** Model facts: aliases, which provider serves a model, output limits and pricing. */
interface ModelCatalogGateway {
    fun defaultModel(): ModelId

    /**
     * Resolves an alias (`sonnet`), a full id (`gpt-5.5`) or a `provider/model` reference
     * (`ollama/llama3.2`). Ids missing from the catalog still resolve when a provider claims them.
     *
     * @throws com.suarvis.butler.core.domain.exception.UnknownModelException when no configured provider serves the model.
     */
    fun resolve(model: ModelId): ModelProfile
}
