package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64

/**
 * Turns the player-response JSON (from `YtEngine.requestPlayerResponse`) plus a PO token
 * (from `requestPoToken`) into a [SabrStreamSpec] for `SabrSession.download`. Pure data mapping.
 * `serverAbrStreamingUrl` already has sig/n decoded (player fetch, not a local decipher).
 */
object SabrSpecBuilder {

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalStateException when the response isn't a SABR stream (no `serverAbrStreamingUrl`). */
    fun build(videoId: String, playerResponseJson: String, poTokenBase64: String?): SabrStreamSpec {
        val data = json.parseToJsonElement(playerResponseJson).jsonObject
        val streaming = data["streamingData"]?.jsonObject ?: error("no streamingData for $videoId")
        val sabrUrl = streaming["serverAbrStreamingUrl"]?.jsonPrimitive?.content
            ?: error("no serverAbrStreamingUrl (SABR-only stream required) for $videoId")
        val ustreamer = data["videoPlaybackUstreamerConfig"]?.jsonPrimitive?.content
            ?: error("no videoPlaybackUstreamerConfig for $videoId")
        val clientVersion = data["clientVersion"]?.jsonPrimitive?.content
        val clientName = data["clientNameId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val formats = adaptiveFormats(streaming)
        val captions = captionEntries(data).map { it.toTrack() }
        return SabrStreamSpec(
            videoId = videoId,
            sabrUrl = sabrUrl,
            ustreamerConfig = ustreamer.decodeBase64Bytes(),
            poToken = poTokenBase64?.takeIf { it.isNotBlank() }?.decodeBase64Bytes(),
            clientInfo = ClientInfo(clientName = clientName, clientVersion = clientVersion, osName = "Windows", osVersion = "10.0"),
            formats = formats,
            captionTracks = captions,
        )
    }

    internal fun adaptiveFormats(streaming: JsonObject): List<AdaptiveFormat> =
        (streaming["adaptiveFormats"] as? JsonArray).orEmpty().map { it.jsonObject.toAdaptiveFormat() }

    private fun JsonObject.toAdaptiveFormat(): AdaptiveFormat {
        val index = this["indexRange"]?.jsonObject
        return AdaptiveFormat(
            itag = long("itag").toInt(),
            lmt = long("lastModified"),
            xtag = str("xtags"),
            mimeType = str("mimeType") ?: "",
            clen = long("contentLength"),
            indexRangeStart = index?.get("start")?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            indexRangeEnd = index?.get("end")?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            width = longOrNull("width")?.toInt(),
            height = longOrNull("height")?.toInt(),
            approxDurationMs = long("approxDurationMs").toInt(),
            audioTrackIsDefault = (this["audioTrack"] as? JsonObject)?.get("audioIsDefault")?.jsonPrimitive?.content?.toBoolean(),
            audioTrackId = (this["audioTrack"] as? JsonObject)?.get("id")?.jsonPrimitive?.content,
            isDrc = this["isDrc"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        )
    }

    /** Decode standard or URL-safe base64 (YouTube mixes both); okio handles both alphabets. */
    private fun String.decodeBase64Bytes(): ByteArray =
        (decodeBase64() ?: trim().decodeBase64())?.toByteArray()
            ?: error("invalid base64")

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLongOrNull()
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
