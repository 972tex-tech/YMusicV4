package ymusic.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import ymusic.ytmedia.sabr.AdaptiveFormat
import ymusic.ytmedia.sabr.CaptionTrack
import ymusic.ytmedia.sabr.ClientInfo
import ymusic.ytmedia.sabr.SabrStreamSpec

/**
 * Turns the decoded player-response JSON (from `WebViewPlayer.requestPlayerResponse`) plus a PO token
 * (from `requestPoToken`) into a [SabrStreamSpec] for `SabrSession.download`. Pure data mapping — the
 * commonMain equivalent of the download probe's `buildSpec`/`toAdaptiveFormat`. The `serverAbrStreamingUrl`
 * already has sig/n decoded by webview-js; this only base64-decodes the ustreamer config + PO token.
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
        val formats = (streaming["adaptiveFormats"] as? JsonArray).orEmpty().map { it.jsonObject.toAdaptiveFormat() }
        val captions = (data["captionTracks"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.toCaptionTrack() }
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

    /** Map one already-cooked `captionTracks` entry (see webview-js `postPlayerRes`) — baseUrl carries
     *  the decoded signature/n + a bound pot, so no URL work here. Skips entries missing a baseUrl. */
    private fun JsonObject.toCaptionTrack(): CaptionTrack? {
        val baseUrl = str("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        val lang = str("languageCode") ?: return null
        return CaptionTrack(
            languageCode = lang,
            name = str("name") ?: lang,
            baseUrl = baseUrl,
            isDefault = this["isDefault"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            isAsr = this["isAsr"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        )
    }

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
