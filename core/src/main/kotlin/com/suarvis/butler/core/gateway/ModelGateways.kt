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

/** Model facts: aliases, provider, output limits and pricing. */
interface ModelCatalogGateway {
    fun defaultModel(): ModelId

    /** Resolves an alias (e.g. `sonnet`) or a full id; unknown ids are passed through with defaults. */
    fun resolve(model: ModelId): ModelProfile
}
