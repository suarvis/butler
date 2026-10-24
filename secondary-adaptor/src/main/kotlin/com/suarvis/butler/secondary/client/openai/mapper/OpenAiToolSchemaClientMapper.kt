package com.suarvis.butler.secondary.client.openai.mapper

import com.suarvis.butler.core.domain.generic.JsonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A tool's input schema as the `parameters` of both OpenAI dialects. Ports Clawcode's
 * `normalize_object_schema`: every object schema, at the top and under `properties` and `items`,
 * gets `properties` (empty) and `additionalProperties: false` when it lacks them, because OpenAI's
 * validator rejects object schemas without them. Fields already set are left alone, so Butler's
 * built-in schemas pass through unchanged.
 */
object OpenAiToolSchemaClientMapper {
    private val OBJECT_TYPE = JsonPrimitive("object")

    /** A schema that is not a JSON object is a bug where the tool was defined, so it fails here instead of being sent. */
    fun toParameters(schema: JsonText): JsonObject = normalize(Json.parseToJsonElement(schema.value).jsonObject)

    private fun normalize(schema: JsonObject): JsonObject {
        val fields = schema.toMutableMap()
        if (schema["type"] == OBJECT_TYPE) {
            fields.getOrPut("properties") { JsonObject(emptyMap()) }
            fields.getOrPut("additionalProperties") { JsonPrimitive(false) }
        }
        (fields["properties"] as? JsonObject)?.let { properties ->
            fields["properties"] = JsonObject(properties.mapValues { (_, property) -> normalizeNode(property) })
        }
        fields["items"]?.let { items -> fields["items"] = normalizeNode(items) }
        return JsonObject(fields)
    }

    /** Only objects are schemas to fill in; anything else (a tuple of `items`, a malformed node) is kept as is. */
    private fun normalizeNode(node: JsonElement): JsonElement = if (node is JsonObject) normalize(node) else node
}
