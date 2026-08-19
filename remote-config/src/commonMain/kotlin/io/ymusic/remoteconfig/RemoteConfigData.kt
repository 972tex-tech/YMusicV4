package io.ymusic.remoteconfig

import io.ymusic.remoteconfig.AppUpdate.Companion.value
import io.ymusic.remoteconfig.BundleVersion.Companion.value
import kmphttp.json.JsonWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** A JS bundle the service publishes. [version] is compared against the revision already on disk. */
data class BundleVersion(val version: Long, val url: String) {
    companion object {
        private const val KEY_VERSION = "version"
        private const val KEY_URL = "url"

        /** Strict, like [AppUpdate.from]: half an entry is a publishing bug, not a value to guess at. */
        fun from(json: JsonObject): BundleVersion = BundleVersion(
            version = json.long(KEY_VERSION),
            url = json.string(KEY_URL),
        )

        fun JsonWriter.value(data: BundleVersion): JsonWriter {
            beginObject()
            name(KEY_VERSION).value(data.version)
            name(KEY_URL).value(data.url)
            endObject()
            return this
        }
    }
}

/** A newer app build, when one exists for this platform. [checksum] is sha256 of the bytes at [url]. */
data class AppUpdate(
    val url: String,
    val changelog: String,
    val size: Long,
    val checksum: String,
) {
    companion object {
        private const val KEY_URL = "url"
        private const val KEY_CHANGELOG = "changelog"
        private const val KEY_SIZE = "size"
        private const val KEY_CHECKSUM = "checksum"

        fun from(json: JsonObject): AppUpdate {
            return AppUpdate(
                url = json.string(KEY_URL),
                changelog = json.string(KEY_CHANGELOG),
                size = json.long(KEY_SIZE),
                checksum = json.string(KEY_CHECKSUM),
            )
        }

        fun JsonWriter.value(data: AppUpdate): JsonWriter {
            beginObject()
            name(KEY_URL).value(data.url)
            name(KEY_CHANGELOG).value(data.changelog)
            name(KEY_SIZE).value(data.size)
            name(KEY_CHECKSUM).value(data.checksum)
            endObject()
            return this
        }
    }
}

/**
 * The answer from `GET /v1/config`.
 *
 * Every field has a default and unknown ones are skipped, so the service can add a flag without
 * breaking a client that predates it, and a client can be built against a field the deployed service
 * does not send yet. That is the whole point of the defaults — do not remove them to make a field
 * "required".
 */
data class RemoteConfigData(
    val variableA: Int = 0,
    val featureX: Boolean = false,
    val newVersion: AppUpdate? = null,
    val wvJs: BundleVersion? = null,
    val engineJs: BundleVersion? = null,
    // client populated timestamp at time data parsed from server
    val updatedAt: Long,
) {
    companion object {
        /** What the app runs on before the first successful fetch, and with no cache to fall back on. */
        val DEFAULTS = RemoteConfigData(updatedAt = 0L)
        private const val KEY_VARIABLE_A = "variableA"
        private const val KEY_FEATURE_X = "featureX"
        private const val KEY_NEW_VERSION = "newVersion"
        private const val KEY_WV_JS = "wvJs"
        private const val KEY_ENGINE_JS = "engineJs"
        private const val KEY_UPDATED_AT = "updatedAt"

        /**
         * [KEY_UPDATED_AT] is when the app last heard from the service, and it is what the throttle
         * and [RemoteConfig.getConfig]'s max-age compare against.
         *
         * The cache carries it, so a restart knows how old its copy is. A server response does not,
         * so [now] stamps it. If a server ever DOES send it, it wins — which is worth knowing, since
         * that hands it the ability to pin clients to a document it declares perpetually fresh.
         */
        fun from(root: JsonObject, now: () -> Long): RemoteConfigData = RemoteConfigData(
            variableA = root.intOrNull(KEY_VARIABLE_A) ?: DEFAULTS.variableA,
            featureX = root.boolOrNull(KEY_FEATURE_X) ?: DEFAULTS.featureX,
            newVersion = root.objectOrNull(KEY_NEW_VERSION)?.let(AppUpdate::from),
            wvJs = root.objectOrNull(KEY_WV_JS)?.let(BundleVersion::from),
            engineJs = root.objectOrNull(KEY_ENGINE_JS)?.let(BundleVersion::from),
            updatedAt = root.longOrNull(KEY_UPDATED_AT) ?: now(),
        )

        fun JsonWriter.value(data: RemoteConfigData): JsonWriter {
            beginObject()
            name(KEY_VARIABLE_A).value(data.variableA)
            name(KEY_FEATURE_X).value(data.featureX)
            data.newVersion?.let { name(KEY_NEW_VERSION).value(it) }
            data.wvJs?.let { name(KEY_WV_JS).value(it) }
            data.engineJs?.let { name(KEY_ENGINE_JS).value(it) }
            // Persisted so a restart knows how old this is, rather than refetching on every launch.
            name(KEY_UPDATED_AT).value(data.updatedAt)
            endObject()
            return this
        }
    }
}

// Read through JsonPrimitive rather than the typed accessors where the service is free to send a
// number as a JSON number or as a string.
private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.string ?: error("Missing string key $key")

private val JsonPrimitive.string: String get() {
    check(isString) { "Not a json string" }
    return content
}

private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.long ?: error("Missing long key $key")

private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject
