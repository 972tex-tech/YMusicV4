package io.ymusic.dynamicengine.internal

import kmpcommon.log.Crumb
import kmpcommon.log.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The caller's logger for one host-protocol serve: the head's `log` field —
 * `{"key": <parked>, "trail": [["tag", {prop}?], …]}` — resolves the parked Kotlin caller (absent →
 * the engine root), gets the JS-side trail grafted on by value, and this protocol's [tag] hop
 * appended. The pre-v2 `logCtx: ["tag", …]` head field still decodes (older remote bundles).
 */
internal suspend fun protocolLogger(parked: ParkedLoggers, head: JsonObject, tag: String): Logger? {
    val wire = head["log"] as? JsonObject
    val key = (wire?.get("key") as? JsonPrimitive)?.intOrNull
    return parked.resolve(key)?.forkAll(readTrail(wire, head))?.fork(tag)
}

private fun readTrail(wire: JsonObject?, head: JsonObject): List<Crumb> {
    val trail = wire?.get("trail") as? JsonArray
    if (trail != null) {
        return trail.mapNotNull { entry ->
            val arr = entry as? JsonArray ?: return@mapNotNull null
            val tag = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val prop = (arr.getOrNull(1) as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.toPropValue()?.let { k to it } }
                ?.toMap()
            Crumb(tag, prop?.takeIf { it.isNotEmpty() })
        }
    }
    return (head["logCtx"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.let(::Crumb) }
}

private fun JsonPrimitive.toPropValue(): Any? =
    if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
