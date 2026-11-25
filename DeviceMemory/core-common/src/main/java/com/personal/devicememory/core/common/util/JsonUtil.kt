package com.personal.devicememory.core.common.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Encode a map to JSON string. Useful for building metadata_json.
 * Full Unicode — no ASCII escaping, so Tamil text is preserved as-is.
 */
fun Map<String, Any?>.toJsonString(): String {
    val jsonObject = buildJsonObject {
        for ((key, value) in this@toJsonString) {
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is List<*> -> put(key, buildJsonArray {
                    for (item in value) {
                        when (item) {
                            is String -> add(JsonPrimitive(item))
                            is Number -> add(JsonPrimitive(item))
                            is Boolean -> add(JsonPrimitive(item))
                            null -> add(JsonNull)
                            else -> add(JsonPrimitive(item.toString()))
                        }
                    }
                })
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }
    return jsonObject.toString()
}
