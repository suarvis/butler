package com.suarvis.butler.secondary.workspace.support

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The file tools' results as Clawcode returns them: each output struct through `to_pretty_json`
 * (`tools/src/lib.rs`), that is serde_json's pretty layout. Envelopes are built key by key, so the
 * keys keep Clawcode's struct order.
 *
 * The layout is written here rather than by kotlinx's `prettyPrint`, which puts an empty array on
 * two lines where serde_json writes `[]`. Scalars use kotlinx's compact form, whose string escapes
 * match serde_json's.
 */
internal object ToolJson {
    fun pretty(envelope: JsonObject): String = StringBuilder().also { write(envelope, it, 0) }.toString()

    fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun write(element: JsonElement, out: StringBuilder, depth: Int) {
        when (element) {
            is JsonObject ->
                block(out, depth, "{", "}", element.entries.toList()) { (key, value) ->
                    out.append(JsonPrimitive(key)).append(": ")
                    write(value, out, depth + 1)
                }
            is JsonArray -> block(out, depth, "[", "]", element) { write(it, out, depth + 1) }
            is JsonPrimitive -> out.append(element)
        }
    }

    // serde_json's `PrettyFormatter`: two-space indent, one item per line, empty containers on one line.
    private fun <T> block(out: StringBuilder, depth: Int, open: String, close: String, items: List<T>, item: (T) -> Unit) {
        out.append(open)
        if (items.isEmpty()) {
            out.append(close)
            return
        }
        items.forEachIndexed { index, value ->
            out.append(if (index == 0) "\n" else ",\n").append(INDENT.repeat(depth + 1))
            item(value)
        }
        out.append("\n").append(INDENT.repeat(depth)).append(close)
    }

    private const val INDENT = "  "
}
