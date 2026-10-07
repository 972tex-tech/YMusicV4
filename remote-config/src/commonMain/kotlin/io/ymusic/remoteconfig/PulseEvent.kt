package io.ymusic.remoteconfig

import kmphttp.json.JsonWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import projectcommon.json.long
import projectcommon.json.objectOrNull
import projectcommon.json.string

/** One anonymous usage event. [at] is epoch ms when it HAPPENED — a queued event keeps its time. */
data class PulseEvent(
    val name: String,
    val at: Long,
    val properties: Map<String, String> = emptyMap(),
) {
    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_AT = "at"
        private const val KEY_PROPS = "props"

        /** Strict: this only ever parses our own queue file, so half an event is a write bug. */
        fun from(json: JsonObject): PulseEvent = PulseEvent(
            name = json.string(KEY_NAME),
            at = json.long(KEY_AT),
            properties = json.objectOrNull(KEY_PROPS)
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                .orEmpty(),
        )

        fun JsonWriter.value(event: PulseEvent): JsonWriter {
            beginObject()
            name(KEY_NAME).value(event.name)
            name(KEY_AT).value(event.at)
            if (event.properties.isNotEmpty()) {
                name(KEY_PROPS).beginObject()
                event.properties.forEach { (key, value) -> name(key).value(value) }
                endObject()
            }
            endObject()
            return this
        }
    }
}
