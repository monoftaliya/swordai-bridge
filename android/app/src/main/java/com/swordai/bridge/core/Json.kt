package com.swordai.bridge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Общий JSON-парсер. Используется только API дерева JsonElement (без плагина сериализации). */
val JsonCodec: Json = Json { ignoreUnknownKeys = true }

fun parseJsonObject(text: String): JsonObject? =
    try {
        JsonCodec.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/** Компактный конструктор JSON; null-значения пропускаются. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    for ((key, value) in pairs) {
        if (value != null) map[key] = toJsonElement(value)
    }
    return JsonObject(map)
}

fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> {
        val map = LinkedHashMap<String, JsonElement>()
        for ((k, v) in value) if (v != null) map[k.toString()] = toJsonElement(v)
        JsonObject(map)
    }
    is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

fun JsonObject.toCompactString(): String = JsonCodec.encodeToString(JsonObject.serializer(), this)
