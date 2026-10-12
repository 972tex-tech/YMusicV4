package projectcommon.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Numbers and booleans parse the primitive's content, so the peer may send a JSON number or a numeric
// string. Strings must be JSON strings. A wrong-typed value reads as absent.

fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

fun JsonObject.stringOrNull(key: String): String? = primitiveOrNull(key)?.takeIf { it.isString }?.content

fun JsonObject.string(key: String): String = stringOrNull(key) ?: error("Missing string key $key")

fun JsonObject.intOrNull(key: String): Int? = primitiveOrNull(key)?.intOrNull

fun JsonObject.int(key: String): Int = intOrNull(key) ?: error("Missing int key $key")

fun JsonObject.longOrNull(key: String): Long? = primitiveOrNull(key)?.longOrNull

fun JsonObject.long(key: String): Long = longOrNull(key) ?: error("Missing long key $key")

fun JsonObject.doubleOrNull(key: String): Double? = primitiveOrNull(key)?.doubleOrNull

fun JsonObject.double(key: String): Double = doubleOrNull(key) ?: error("Missing double key $key")

fun JsonObject.boolOrNull(key: String): Boolean? = primitiveOrNull(key)?.booleanOrNull

fun JsonObject.bool(key: String): Boolean = boolOrNull(key) ?: error("Missing bool key $key")

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objects(key: String): List<JsonObject> = arrayOrNull(key).orEmpty().filterIsInstance<JsonObject>()
