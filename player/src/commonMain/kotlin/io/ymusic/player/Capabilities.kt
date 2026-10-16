package io.ymusic.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-specific **state** an engine exposes — the available video qualities, caption tracks, and
 * audio tracks it discovered for the current source. A caller queries at runtime:
 * `(engine as? VideoQualitySelectable)?.videoQuality`. Today the YouTube engine implements
 * all three; the native-local engine implements none (yet).
 *
 * *Selecting* a quality/caption is not here — it goes through [PlaybackConfig] (`preferVideoQuality`,
 * `preferCaptionLanguage`, `captionVisible`) pushed via [MediaEngine.setConfig], so every setting
 * travels one path. Audio-track selection is the one immediate action ([AudioTrackSelectable]), since
 * it is a one-shot switch, not a persistent preference the engine restores across loads.
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

        /** Choices every local source with a video track supports, in addition to its resolution tiers. */
        val LOCAL_BASE_OPTIONS = listOf(
            VideoQualityOption(AUDIO_ONLY, "Audio-only"),
            VideoQualityOption(AUTO, "Auto"),
        )

        /** YouTube-style quality tiers keyed by min video dimension (px), ascending. */
        private val LADDER = listOf(
            VideoQualityOption("tiny", "144p") to 144,
            VideoQualityOption("small", "240p") to 240,
            VideoQualityOption("medium", "360p") to 360,
            VideoQualityOption("large", "480p") to 480,
            VideoQualityOption("hd720", "720p") to 720,
            VideoQualityOption("hd1080", "1080p") to 1080,
            VideoQualityOption("hd1440", "1440p") to 1440,
            VideoQualityOption("hd2160", "4K") to 2160,
            VideoQualityOption("hd2880", "5K") to 2880,
            VideoQualityOption("highres", "8K") to 4320,
        )

        /** The nearest ladder option at or below [heightPx] (a local track's resolution → a tier). */
        fun optionForHeight(heightPx: Int): VideoQualityOption =
            LADDER.lastOrNull { heightPx >= it.second }?.first ?: LADDER.first().first

        /** The pixel height a ladder [quality] id maps to, or null for AUTO/AUDIO_ONLY/unknown. */
        fun heightForQuality(quality: String): Int? = LADDER.firstOrNull { it.first.quality == quality }?.second
    }
}

/** An engine that reports its available video qualities. Selection is via [PlaybackConfig.preferVideoQuality]. */
interface VideoQualitySelectable {
    val videoQuality: StateFlow<VideoQualityState>
}

// --- Captions ---

data class CaptionTrack(
    val languageCode: String,
    val displayName: String,
    val vssId: String? = null,
    val kind: String = "",
) {
    /** Selection key: YouTube `vssId` when known, else [languageCode]. */
    val id: String get() = vssId ?: languageCode
}

data class CaptionLine(val id: Int, val text: String)

/** Timed cues injected into a local file player (YouTube ASR for a downloaded mp4, sidecar, …). */
data class InjectedCaptionCue(val startSec: Double, val endSec: Double, val text: String)

data class InjectedCaptionTrack(
    val id: String,
    val languageCode: String,
    val displayName: String,
    val cues: List<InjectedCaptionCue>,
)

/** [selectedLanguage] is null when captions are off. */
data class CaptionState(
    val tracks: List<CaptionTrack>,
    val selectedLanguage: String?,
) {
    companion object {
        val DEFAULT = CaptionState(emptyList(), null)
    }
}

/**
 * An engine that reports its available caption tracks. Selection/visibility is via
 * [PlaybackConfig.preferCaptionLanguage] and [PlaybackConfig.captionVisible].
 */
interface CaptionSelectable {
    val captions: StateFlow<CaptionState>
}

/** Live paint-on lines for a Compose overlay. Empty when captions are off or the engine has none. */
interface CaptionRenderable {
    val captionLines: StateFlow<List<CaptionLine>>
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

// --- System audio effects (Android) ---

/** An engine whose audio output has an Android audio session; 0 while it has none. */
interface AudioSessionSource {
    val audioSessionId: StateFlow<Int>
}
