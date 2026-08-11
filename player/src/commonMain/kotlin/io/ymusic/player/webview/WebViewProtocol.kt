package io.ymusic.player.webview

import io.ymusic.player.AudioTrack
import io.ymusic.player.AudioTrackState
import io.ymusic.player.CaptionState
import io.ymusic.player.CaptionTrack
import io.ymusic.player.VideoQualityOption
import io.ymusic.player.VideoQualityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The pure wire protocol for the WebView YouTube engine — the KMP counterpart of the legacy
 * `WebViewYtPlayer`'s `Actions`/channel bridge. Platform-independent and side-effect-free: it only
 * builds command JSON and parses event JSON, so it is fully unit-testable without a WebView.
 *
 * Two directions (see the injected `webview_ymusic` bundle):
 *  - **host → page**: a command JSON string passed to `window.ymusicAction(<json>)`. Each command is
 *    an object with an integer `what` discriminator ([encodeLoad] etc.).
 *  - **page → host**: the page posts `"${sessionId}_<name>"` channels with a JSON payload
 *    ([decodeEvent] strips the session prefix and returns a typed [WebViewEvent]).
 *
 * Covers the narrow [io.ymusic.player.MediaEngine] surface plus the YouTube capability channels
 * (video quality / captions / audio tracks — the `*Selectable` interfaces). `po-token` and
 * `player-response` remain out of scope (extraction concerns, not playback).
 */
object WebViewProtocol {
    // `what` discriminators — must match Actions.kt (legacy) and player_connect.ts (the page agent).
    private const val LOAD = 0
    private const val SET_PWR = 2
    private const val SET_QUALITY = 3
    private const val SEEK = 4
    private const val STOP = 5
    private const val SET_PREFER_CAPTION_LANGUAGE = 7
    private const val SET_AUDIO_TRACK = 8
    private const val SET_CAPTION_VISIBLE = 9
    private const val SET_VOLUME = 10
    private const val SET_PLAY_SPEED = 11
    private const val BULK = 12
    private const val ADD_WANTED_PLAYER_RES = 13
    private const val GET_PO_TOKEN = 16
    private const val PRELOAD_VIDEO = 1

    /** The video-quality token the page treats as audio-only (hides the picture, keeps audio). */
    const val QUALITY_AUDIO_ONLY = "zero"
    const val QUALITY_AUTO = "auto"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A single `ymusicAction` command: `Load`, optionally batched (via `Bulk`) with the initial
     * volume/speed/audio-only config so they apply before the first frame. Returns the JSON string
     * to eval as `window.ymusicAction(<returned>)`.
     */
    fun encodeLoad(
        videoId: String,
        startPositionMs: Long,
        playWhenReady: Boolean,
        volume: Float,
        speed: Float,
        audioOnly: Boolean,
    ): String {
        val load = buildJsonObject {
            put("what", JsonPrimitive(LOAD))
            put("videoId", JsonPrimitive(videoId))
            put("startTimeSeconds", JsonPrimitive(startPositionMs / 1000.0))
            put("playWhenReady", JsonPrimitive(playWhenReady))
        }
        val batch = buildList {
            add(load)
            add(volumeObject(volume))
            add(speedObject(speed))
            add(qualityObject(if (audioOnly) QUALITY_AUDIO_ONLY else QUALITY_AUTO))
        }
        return buildJsonObject {
            put("what", JsonPrimitive(BULK))
            put("actions", JsonArray(batch))
        }.toString()
    }

    /**
     * Batch several already-encoded commands into ONE `ymusicAction` round trip (the page's BULK
     * action, applied in order within a single JS task — no page event can interleave). A single
     * command passes through unwrapped; the list must not be empty.
     */
    fun encodeBulk(commands: List<String>): String {
        require(commands.isNotEmpty()) { "empty bulk" }
        commands.singleOrNull()?.let { return it }
        return buildJsonObject {
            put("what", JsonPrimitive(BULK))
            put("actions", JsonArray(commands.map { json.parseToJsonElement(it).jsonObject }))
        }.toString()
    }

    fun encodePlay(): String = pwr(true)

    fun encodePause(): String = pwr(false)

    fun encodeSeek(positionMs: Long): String = buildJsonObject {
        put("what", JsonPrimitive(SEEK))
        put("positionSeconds", JsonPrimitive(positionMs / 1000.0))
    }.toString()

    fun encodeStop(): String = buildJsonObject {
        put("what", JsonPrimitive(STOP))
    }.toString()

    fun encodeSetVolume(volume: Float): String = volumeObject(volume).toString()

    fun encodeSetSpeed(speed: Float): String = speedObject(speed).toString()

    /** Audio-only is expressed as video quality `"zero"` (page hides the picture, keeps audio). */
    fun encodeSetAudioOnly(audioOnly: Boolean): String =
        qualityObject(if (audioOnly) QUALITY_AUDIO_ONLY else QUALITY_AUTO).toString()

    // --- capability commands ---

    fun encodeSelectVideoQuality(quality: String): String = qualityObject(quality).toString()

    fun encodeSelectCaption(languageCode: String?): String = buildJsonObject {
        put("what", JsonPrimitive(SET_PREFER_CAPTION_LANGUAGE))
        put("languageCode", languageCode?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    fun encodeSetCaptionVisible(visible: Boolean): String = buildJsonObject {
        put("what", JsonPrimitive(SET_CAPTION_VISIBLE))
        put("visible", JsonPrimitive(visible))
    }.toString()

    fun encodeSelectAudioTrack(trackId: String): String = buildJsonObject {
        put("what", JsonPrimitive(SET_AUDIO_TRACK))
        put("trackId", JsonPrimitive(trackId))
    }.toString()

    // --- extraction commands (for the SABR downloader / player cache) ---

    /**
     * Ask the page for the decoded player response for [videoId] on the `player_res` channel. Sent as a
     * BULK of ADD_WANTED then PRELOAD: register the want **first** so the page is already listening when
     * the preload's `/player` fetch lands (no lost-response race), then preload makes it **fetch** that
     * response (sig/n decoded, cached) without playing — so this works for any video, not just the one on
     * screen, and doesn't disturb what's currently playing. A cached / currently-playing response posts
     * immediately on ADD_WANTED.
     */
    fun encodeRequestPlayerResponse(videoId: String): String = buildJsonObject {
        put("what", JsonPrimitive(BULK))
        put(
            "actions",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("what", JsonPrimitive(ADD_WANTED_PLAYER_RES))
                        put("videoId", JsonPrimitive(videoId))
                    },
                )
                add(
                    buildJsonObject {
                        put("what", JsonPrimitive(PRELOAD_VIDEO))
                        put("videoId", JsonPrimitive(videoId))
                    },
                )
            },
        )
    }.toString()

    /** Ask the page to mint a GVS PO token for [videoId]; result arrives on the `po_token_v2` channel. */
    fun encodeRequestPoToken(videoId: String, timeoutMs: Long): String = buildJsonObject {
        put("what", JsonPrimitive(GET_PO_TOKEN))
        put("videoId", JsonPrimitive(videoId))
        put("timeout", JsonPrimitive(timeoutMs))
    }.toString()

    /**
     * Decode a page → host channel. [channel] is the raw channel name (`"${sessionId}_state_changed"`,
     * or `"report_error"` which carries no prefix). Returns null for an unknown channel, a wrong
     * session, or an unparseable payload (the caller ignores those).
     */
    fun decodeEvent(channel: String, payload: String, sessionId: String): WebViewEvent? {
        val prefix = sessionId + "_"
        val name = if (channel.startsWith(prefix)) channel.substring(prefix.length) else channel
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        return when (name) {
            "connected" -> WebViewEvent.Connected
            "state_changed" -> WebViewEvent.StateChanged(
                state = mapState(obj.string("newState")),
                playWhenReady = obj.bool("playWhenReady"),
                errorCode = obj.string("code"),
                errorMessage = obj.string("message"),
                // Absent flags default like the legacy host: retryable, no reset needed.
                errorCanRetry = obj.boolOr("canRetry", default = true),
                errorNeedReset = obj.boolOr("needReset", default = false),
            )
            "progress" -> WebViewEvent.Progress(
                positionMs = obj.seconds("positionSeconds"),
                durationMs = obj.seconds("durationSeconds"),
                bufferedMs = obj.seconds("bufferedSeconds"),
                seekable = obj.bool("seekable"),
            )
            "quality_changed" -> WebViewEvent.QualityChanged(
                VideoQualityState(
                    options = obj.qualityOptions("available"),
                    actualQuality = obj.string("actual") ?: VideoQualityState.AUTO,
                    preferredQuality = obj.string("prefer") ?: VideoQualityState.AUTO,
                ),
            )
            "captions" -> WebViewEvent.CaptionsChanged(
                CaptionState(
                    tracks = obj.captionTracks("tracks"),
                    selectedLanguage = obj.string("selectedCaptionLanguage"),
                ),
            )
            "audio_tracks" -> WebViewEvent.AudioTracksChanged(
                AudioTrackState(
                    tracks = obj.audioTrackList("tracks"),
                    selectedId = obj.string("selectedTrackId"),
                ),
            )
            // The page's watch-next payload for a music video. The host never reads inside it — it
            // is re-serialized as-is for the scraping engine, which owns every YouTube shape.
            "next_contents" -> {
                val videoId = obj.string("videoId")
                val contents = obj["watchNextContents"] as? kotlinx.serialization.json.JsonArray
                if (videoId == null || contents == null) null else WebViewEvent.WatchNextSeen(videoId, contents.toString())
            }
            // Decoded player response for a wanted videoId. `data` (streamingData with sig/n-decoded
            // serverAbrStreamingUrl, adaptiveFormats, videoPlaybackUstreamerConfig, client info) stays
            // opaque here — the SABR layer parses it.
            "player_res" -> {
                val videoId = obj.string("videoId")
                val data = obj["data"] as? JsonObject
                if (videoId == null || data == null) null else WebViewEvent.PlayerResponse(videoId, data.toString())
            }
            "po_token_v2" -> {
                val videoId = obj.string("videoId") ?: return null
                if (obj.boolOr("success", default = false)) {
                    WebViewEvent.PoToken(videoId, token = obj.string("token"), errorMessage = null)
                } else {
                    WebViewEvent.PoToken(videoId, token = null, errorMessage = obj.string("message") ?: "po token failed")
                }
            }
            else -> null
        }
    }

    private fun pwr(playWhenReady: Boolean): String = buildJsonObject {
        put("what", JsonPrimitive(SET_PWR))
        put("playWhenReady", JsonPrimitive(playWhenReady))
    }.toString()

    private fun volumeObject(volume: Float): JsonObject = buildJsonObject {
        put("what", JsonPrimitive(SET_VOLUME))
        put("volume", JsonPrimitive(volume.coerceIn(0f, 1f)))
    }

    private fun speedObject(speed: Float): JsonObject = buildJsonObject {
        put("what", JsonPrimitive(SET_PLAY_SPEED))
        put("speed", JsonPrimitive(speed))
        put("pitchFactor", JsonPrimitive(1f))
    }

    private fun qualityObject(quality: String): JsonObject = buildJsonObject {
        put("what", JsonPrimitive(SET_QUALITY))
        put("quality", JsonPrimitive(quality))
    }

    private fun mapState(newState: String?): WebPlaybackState = when (newState) {
        "idle" -> WebPlaybackState.Idle
        "buffering" -> WebPlaybackState.Buffering
        "ready" -> WebPlaybackState.Ready
        "ended" -> WebPlaybackState.Ended
        "error" -> WebPlaybackState.Error
        else -> WebPlaybackState.Idle
    }

    private fun JsonObject.string(key: String): String? {
        val p = this[key]?.jsonPrimitive ?: return null
        return if (p is JsonNull) null else p.content
    }

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.content == "true"

    private fun JsonObject.boolOr(key: String, default: Boolean): Boolean {
        val p = this[key]?.jsonPrimitive ?: return default
        return if (p is JsonNull) default else p.content == "true"
    }

    /** Seconds (possibly fractional) → whole milliseconds; absent/malformed → 0. */
    private fun JsonObject.seconds(key: String): Long {
        val s = this[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        return (s * 1000.0).toLong()
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.qualityOptions(key: String): List<VideoQualityOption> =
        objects(key).mapNotNull { o ->
            val q = o.string("quality") ?: return@mapNotNull null
            VideoQualityOption(q, o.string("label") ?: q)
        }

    private fun JsonObject.captionTracks(key: String): List<CaptionTrack> =
        objects(key).mapNotNull { o ->
            val lang = o.string("languageCode") ?: o.string("actualLanguageCode") ?: return@mapNotNull null
            CaptionTrack(lang, o.string("displayName") ?: lang)
        }

    private fun JsonObject.audioTrackList(key: String): List<AudioTrack> =
        objects(key).mapNotNull { o ->
            val id = o.string("trackId") ?: return@mapNotNull null
            AudioTrack(id, o.string("displayName") ?: id)
        }
}

/** A decoded page → host event; the engine maps these onto its `status`/`progress` flows. */
sealed interface WebViewEvent {
    /** The page agent booted and connected (the injected bundle is live). */
    data object Connected : WebViewEvent

    data class StateChanged(
        val state: WebPlaybackState,
        val playWhenReady: Boolean,
        val errorCode: String?,
        val errorMessage: String?,
        val errorCanRetry: Boolean = true,
        val errorNeedReset: Boolean = false,
    ) : WebViewEvent

    data class Progress(
        val positionMs: Long,
        val durationMs: Long,
        val bufferedMs: Long,
        val seekable: Boolean,
    ) : WebViewEvent

    data class QualityChanged(val state: VideoQualityState) : WebViewEvent

    data class CaptionsChanged(val state: CaptionState) : WebViewEvent

    data class AudioTracksChanged(val state: AudioTrackState) : WebViewEvent

    /**
     * The page reported the watch-next payload of the music video it is playing — the raw JSON array
     * of related items, opaque here. It feeds the app's Recommended feed; the scraping engine is the
     * only thing that parses it.
     */
    data class WatchNextSeen(val videoId: String, val contentsJson: String) : WebViewEvent

    /** The page's decoded player response for [videoId]; [dataJson] is the opaque `data` object. */
    data class PlayerResponse(val videoId: String, val dataJson: String) : WebViewEvent

    /** A minted GVS PO token (base64) for [videoId], or [errorMessage] on failure. */
    data class PoToken(val videoId: String, val token: String?, val errorMessage: String?) : WebViewEvent
}

enum class WebPlaybackState { Idle, Buffering, Ready, Ended, Error }
