package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat

/**
 * Chooses the concrete [AdaptiveFormat]s to download for a [DownloadFormat] from a video's
 * `adaptiveFormats`. Audio and video are separate elementary streams (SABR delivers each unmuxed); the
 * pipeline muxes/transcodes afterward. `contentLength` (`clen`) stands in for bitrate — there is no
 * explicit bitrate field — so "best" means largest `clen` within the preferred codec family.
 */
internal object FormatSelection {

    /**
     * Best audio track. [preferAac] biases toward mp4/AAC (so an M4A/MP4 target can stream-copy or make
     * a compatible AAC track); otherwise WebM/Opus is preferred (higher quality per byte for MP3/WebM).
     * The requested [audioTrackId] (a dubbed language) wins when the video has it; otherwise default
     * (non-dubbed) tracks win over alternates so we don't grab a foreign-language dub.
     */
    fun bestAudio(formats: List<AdaptiveFormat>, preferAac: Boolean, audioTrackId: String? = null): AdaptiveFormat? {
        val audio = formats.filter { it.mimeType.startsWith("audio") && it.clen > 0 && !it.isDrc }
        if (audio.isEmpty()) return null
        return audio.sortedWith(
            compareByDescending<AdaptiveFormat> { audioTrackId != null && it.audioTrackId == audioTrackId }
                .thenByDescending { it.audioTrackIsDefault != false }
                .thenByDescending { if (preferAac) it.mimeType.contains("mp4") else it.mimeType.contains("webm") }
                .thenByDescending { it.clen },
        ).first()
    }

    /**
     * Video track nearest [requestedHeight] (or the highest when null), biased to the codec family that
     * stream-copies into [container]: VP9/WebM for a WebM output, AVC/mp4 for an MP4 output (VP9-in-mp4
     * copies but plays back poorly on many devices). Ties break on larger [AdaptiveFormat.clen].
     */
    fun videoNearest(
        formats: List<AdaptiveFormat>,
        requestedHeight: Int?,
        container: DownloadFormat,
    ): AdaptiveFormat? {
        val video = formats.filter { it.isVideo && it.clen > 0 && it.height != null }
        if (video.isEmpty()) return null
        val preferMp4Codec = container == DownloadFormat.VIDEO_MP4
        return video.sortedWith(
            // 1) closeness to the requested height (0 distance when unspecified → pure quality sort)
            compareBy<AdaptiveFormat> { requestedHeight?.let { req -> kotlin.math.abs((it.height ?: 0) - req) } ?: 0 }
                // 2) codec that copies cleanly into the target container
                .thenByDescending { if (preferMp4Codec) it.mimeType.contains("mp4") else it.mimeType.contains("webm") }
                // 3) higher resolution, then bigger stream (bitrate proxy)
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.clen },
        ).first()
    }
}
