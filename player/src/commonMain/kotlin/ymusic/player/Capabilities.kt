package ymusic.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-specific capabilities — the narrow [MediaEngine] core stays free of features not every
 * backend has. An engine implements only the capabilities it supports; a caller queries at runtime:
 * `(engine as? VideoQualitySelectable)?.selectVideoQuality(...)`. Today the WebView/YouTube engine
 * implements all three; the native-local engine implements none (yet). This is the alternative to
 * the legacy `BasicMediaPlayer` fat superset where every impl had to stub what it lacked.
 */

// --- Video quality (YouTube) ---

data class VideoQualityOption(val quality: String, val label: String)

/** [actualQuality] is what's playing; [preferredQuality] is the user's setting (may be `AUTO`). */
data class VideoQualityState(
    val options: List<VideoQualityOption>,
    val actualQuality: String,
    val preferredQuality: String,
) {
    companion object {
        const val AUTO = "auto"
        const val AUDIO_ONLY = "zero"
        val DEFAULT = VideoQualityState(emptyList(), AUTO, AUTO)
    }
}

interface VideoQualitySelectable {
    val videoQuality: StateFlow<VideoQualityState>

    /** Select a [VideoQualityOption.quality], or [VideoQualityState.AUTO] / [VideoQualityState.AUDIO_ONLY]. */
    fun selectVideoQuality(quality: String)
}

// --- Captions ---

data class CaptionTrack(val languageCode: String, val displayName: String)

/** [selectedLanguage] is null when captions are off. */
data class CaptionState(
    val tracks: List<CaptionTrack>,
    val selectedLanguage: String?,
) {
    companion object {
        val DEFAULT = CaptionState(emptyList(), null)
    }
}

interface CaptionSelectable {
    val captions: StateFlow<CaptionState>

    /** Select a [CaptionTrack.languageCode], null (auto), or `"off"` to disable. */
    fun selectCaption(languageCode: String?)

    fun setCaptionVisible(visible: Boolean)
}

// --- Audio tracks (multi-language YouTube audio) ---

data class AudioTrack(val id: String, val displayName: String)

data class AudioTrackState(
    val tracks: List<AudioTrack>,
    val selectedId: String?,
) {
    companion object {
        val DEFAULT = AudioTrackState(emptyList(), null)
    }
}

interface AudioTrackSelectable {
    val audioTracks: StateFlow<AudioTrackState>

    fun selectAudioTrack(id: String)
}
