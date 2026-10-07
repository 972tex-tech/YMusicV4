package io.ymusic.ytmedia.sabr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.decodeBase64

/** engine-js SABR POST header: base64 (url-safe or standard) of the /player adaptiveFormats JSON array. */
const val ADAPTIVE_FORMATS_HEADER = "X-Ym-Af"

/** @throws IllegalArgumentException / IllegalStateException on a malformed header. */
fun parseAdaptiveFormatsHeader(value: String): List<AdaptiveFormat> {
    val text = requireNotNull(value.decodeBase64()) { "$ADAPTIVE_FORMATS_HEADER is not base64" }.utf8()
    val root = Json.parseToJsonElement(text) as? JsonArray ?: error("$ADAPTIVE_FORMATS_HEADER is not a JSON array")
    return root.filterIsInstance<JsonObject>().map { it.toAdaptiveFormat() }
}
