package com.suarvis.butler.secondary.client.openai.mapper

import com.suarvis.butler.core.domain.generic.JsonText
import com.suarvis.butler.core.domain.tool.BuiltInTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Clawcode's `normalize_object_schema` cases, plus objects inside arrays and Butler's own schemas. */
class OpenAiToolSchemaClientMapperTest {
    private fun parameters(schema: String): JsonObject = OpenAiToolSchemaClientMapper.toParameters(JsonText(schema))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.property(name: String): JsonObject = getValue("properties").jsonObject.getValue(name).jsonObject

    @Test
    fun `a bare object schema gets empty properties and is closed`() {
        assertEquals(json("""{"type":"object","properties":{},"additionalProperties":false}"""), parameters("""{"type":"object"}"""))
    }

    @Test
    fun `object schemas nested in properties are closed too`() {
        val result = parameters("""{"type":"object","properties":{"location":{"type":"object","properties":{"lat":{"type":"number"}}}}}""")

        assertEquals(JsonPrimitive(false), result["additionalProperties"])
        assertEquals(json("""{"type":"object","properties":{"lat":{"type":"number"}},"additionalProperties":false}"""), result.property("location"))
    }

    @Test
    fun `fields already set are left alone`() {
        val schema = """{"type":"object","properties":{"x":{"type":"string"}},"additionalProperties":true}"""

        assertEquals(json(schema), parameters(schema))
    }

    @Test
    fun `object schemas inside arrays are closed`() {
        val result = parameters("""{"type":"object","properties":{"edits":{"type":"array","items":{"type":"object","properties":{"old":{"type":"string"}}}}}}""")

        val items = result.property("edits").getValue("items").jsonObject
        assertEquals(json("""{"type":"object","properties":{"old":{"type":"string"}},"additionalProperties":false}"""), items)
    }

    @Test
    fun `built-in schemas pass through unchanged`() {
        BuiltInTools.ALL.forEach { tool ->
            assertEquals(json(tool.inputSchema.value), OpenAiToolSchemaClientMapper.toParameters(tool.inputSchema), tool.name.value)
        }
    }

    @Test
    fun `a schema that is not a JSON object is rejected`() {
        assertThrows<IllegalArgumentException> { parameters("""["not", "an", "object"]""") }
    }
}
