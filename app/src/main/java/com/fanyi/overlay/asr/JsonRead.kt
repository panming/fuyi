package com.fanyi.overlay.asr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun JsonElement?.stringOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

internal fun JsonElement?.boolOrNull(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull ?: contentOrNull?.toBooleanStrictOrNull()
    else -> null
}

internal fun JsonElement?.intOrNull(): Int? = when (this) {
    is JsonPrimitive -> intOrNull ?: contentOrNull?.toIntOrNull()
    else -> null
}

internal fun JsonElement?.longOrNull(): Long? = when (this) {
    is JsonPrimitive -> longOrNull ?: contentOrNull?.toLongOrNull()
    else -> null
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.child(name: String): JsonElement? = this[name]

internal fun JsonElement.obj(): JsonObject = jsonObject

internal fun JsonPrimitive.text(): String = contentOrNull.orEmpty()
