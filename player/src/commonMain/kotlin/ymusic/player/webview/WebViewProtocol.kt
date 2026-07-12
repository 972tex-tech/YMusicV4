package ymusic.player.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ymusic.player.AudioTrack
import ymusic.player.AudioTrackState
import ymusic.player.CaptionState
import ymusic.player.CaptionTrack
import ymusic.player.PlaybackState
import ymusic.player.VideoQualityOption
import ymusic.player.VideoQualityState

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
 * Covers the narrow [ymusic.player.MediaEngine] surface plus the YouTube capability channels
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

    private fun mapState(newState: String?): PlaybackState = when (newState) {
        "idle" -> PlaybackState.Idle
        "buffering" -> PlaybackState.Buffering
        "ready" -> PlaybackState.Ready
        "ended" -> PlaybackState.Ended
        "error" -> PlaybackState.Error
        else -> PlaybackState.Idle
    }

    private fun JsonObject.string(key: String): String? {
        val p = this[key]?.jsonPrimitive ?: return null
        return if (p is JsonNull) null else p.content
    }

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.content == "true"

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
        val state: PlaybackState,
        val playWhenReady: Boolean,
        val errorCode: String?,
        val errorMessage: String?,
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
}
