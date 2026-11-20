package cn.daqing.leap.a10energy.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

fun JsonElement?.asLong(): Long? {
    val p = asPrimitive() ?: return null
    p.longOrNull?.let { return it }
    p.doubleOrNull?.let { return it.toLong() }
    val text = p.contentOrNull ?: return null
    text.toLongOrNull()?.let { return it }
    return text.toDoubleOrNull()?.toLong()
}

fun JsonElement?.asDouble(): Double? {
    val p = asPrimitive() ?: return null
    p.doubleOrNull?.let { return it }
    p.longOrNull?.let { return it.toDouble() }
    return p.contentOrNull?.toDoubleOrNull()
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.str(key: String): String? {
    val text = this[key].asPrimitive()?.contentOrNull ?: return null
    return text.takeIf { it.isNotBlank() && it != "null" }
}

fun JsonObject.longFlex(key: String): Long? = this[key].asLong()

fun JsonObject.intFlex(key: String): Int? = this[key].asLong()?.toInt()

fun JsonObject.doubleFlex(key: String): Double? = this[key].asDouble()

fun JsonObject.firstNumber(vararg keys: String): Double? {
    for (key in keys) {
        doubleFlex(key)?.let { return it }
    }
    return null
}

fun JsonObject.firstInt(vararg keys: String): Int? {
    for (key in keys) {
        intFlex(key)?.let { return it }
    }
    return null
}

fun JsonObject.firstLong(vararg keys: String): Long? {
    for (key in keys) {
        longFlex(key)?.let { return it }
    }
    return null
}
