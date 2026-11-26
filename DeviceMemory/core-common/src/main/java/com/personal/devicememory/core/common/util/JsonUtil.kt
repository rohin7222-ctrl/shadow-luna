package com.personal.devicememory.core.common.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Encode a map to JSON string cleanly, preserving recursive nested maps/lists and full Unicode (Tamil/English).
 * Guards non-finite Double/Float values (NaN / Infinity) as JSON `null`.
 */
fun Map<String, Any?>.toJsonString(): String = toJsonElement(this).toString()

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in value) {
            if (k != null) {
                put(k.toString(), toJsonElement(v))
            }
        }
    }
    is Iterable<*> -> buildJsonArray {
        for (item in value) {
            add(toJsonElement(item))
        }
    }
    is Array<*> -> buildJsonArray {
        for (item in value) {
            add(toJsonElement(item))
        }
    }
    else -> JsonPrimitive(value.toString())
}
