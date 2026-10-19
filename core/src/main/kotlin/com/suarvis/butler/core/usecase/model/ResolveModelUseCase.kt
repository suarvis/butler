package com.suarvis.butler.core.usecase.model

import com.suarvis.butler.core.domain.generic.ModelId
import com.suarvis.butler.core.domain.model.ModelProfile
import com.suarvis.butler.core.gateway.ModelCatalogGateway

/**
 * Looks up a model before it is used, so `/model gpt-5.5` can confirm the provider (or reject an
 * unknown model) straight away instead of failing on the next prompt.
 */
class ResolveModelUseCase(private val catalog: ModelCatalogGateway) {
    /** @param model an alias, a full id or `provider/model`; null means the configured default. */
    fun execute(model: ModelId?): ModelProfile = catalog.resolve(model ?: catalog.defaultModel())
}
