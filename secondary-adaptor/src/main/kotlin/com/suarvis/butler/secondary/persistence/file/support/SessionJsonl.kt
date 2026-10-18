package com.suarvis.butler.secondary.persistence.file.support

import com.suarvis.butler.secondary.persistence.file.entity.SessionLineRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/** Encoding and decoding of session JSONL lines. */
object SessionJsonl {
    /** Line types this version understands. Unknown types written by newer tools are skipped, not rejected. */
    private val KNOWN_TYPES = setOf("session_meta", "message", "compaction", "prompt_history")

    private val lineSerializer = serializer<SessionLineRecord>()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    fun encode(record: SessionLineRecord): String = json.encodeToString(lineSerializer, record)

    /** Returns null for blank lines and for record types this version does not know. */
    fun decode(line: String): SessionLineRecord? {
        if (line.isBlank()) return null
        val element: JsonObject = json.parseToJsonElement(line).jsonObject
        val type = element["type"]?.jsonPrimitive?.content
        if (type !in KNOWN_TYPES) return null
        return json.decodeFromJsonElement(lineSerializer, element)
    }
}
