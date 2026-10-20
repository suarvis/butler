package com.suarvis.butler.secondary.persistence.file.support

import kotlinx.serialization.json.Json

/** JSON settings for the per-user files under `~/.butler` (usage ledger, rate limits). */
object UserFileJson {
    val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
}
