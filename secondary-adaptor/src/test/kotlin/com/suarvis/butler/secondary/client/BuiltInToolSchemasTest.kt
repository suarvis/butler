package com.suarvis.butler.secondary.client

import com.suarvis.butler.core.domain.generic.ToolName
import com.suarvis.butler.core.domain.tool.BuiltInTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Core keeps tool schemas as opaque JSON text, so this module, which has a JSON library and sends
 * them to providers, checks they are the object schemas every provider accepts.
 */
class BuiltInToolSchemasTest {
    @ParameterizedTest
    @MethodSource("tools")
    fun `schema is a closed JSON object schema`(name: String) {
        val tool = requireNotNull(BuiltInTools.byName(ToolName(name)))
        val schema = Json.parseToJsonElement(tool.inputSchema.value).jsonObject

        assertEquals(JsonPrimitive("object"), schema["type"])
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        val properties = schema.getValue("properties").jsonObject
        assertTrue(properties.isNotEmpty())
        properties.forEach { (field, property) ->
            assertTrue(property is JsonObject && property["type"] is JsonPrimitive, "$name.$field needs a type")
        }
        schema.getValue("required").jsonArray.forEach { required ->
            assertTrue(required.jsonPrimitive.content in properties, "$name requires ${required.jsonPrimitive.content}, which is not a property")
        }
    }

    companion object {
        @JvmStatic
        fun tools(): List<String> = BuiltInTools.ALL.map { it.name.value }
    }
}
