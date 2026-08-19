package io.ymusic.remoteconfig

import kmphttp.json.JsonWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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
            name = checkNotNull(json[KEY_NAME]?.jsonPrimitive?.content) { "Missing $KEY_NAME" },
            at = checkNotNull(json[KEY_AT]?.jsonPrimitive).long,
            properties = (json[KEY_PROPS] as? JsonObject)
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
