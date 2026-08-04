package ymusic.download

import ymusic.ytmedia.sabr.AdaptiveFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatSelectionTest {

    private fun audio(itag: Int, mime: String, clen: Long, default: Boolean? = true, drc: Boolean = false) =
        AdaptiveFormat(
            itag = itag, lmt = 1, xtag = null, mimeType = mime, clen = clen,
            indexRangeStart = 0, indexRangeEnd = 0, width = null, height = null,
            approxDurationMs = 1000, audioTrackIsDefault = default, audioTrackId = null, isDrc = drc,
        )

    private fun video(itag: Int, mime: String, height: Int, clen: Long) = AdaptiveFormat(
        itag = itag, lmt = 1, xtag = null, mimeType = mime, clen = clen,
        indexRangeStart = 0, indexRangeEnd = 0, width = height * 16 / 9, height = height,
        approxDurationMs = 1000, audioTrackIsDefault = null, audioTrackId = null, isDrc = false,
    )

    private val formats = listOf(
        audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 3_000_000),
        audio(251, "audio/webm; codecs=\"opus\"", 3_500_000),
        audio(233, "audio/mp4; codecs=\"mp4a.40.2\"", 500_000, default = false), // dubbed alt track
        video(160, "video/mp4; codecs=\"avc1\"", 144, 1_000_000),
        video(136, "video/mp4; codecs=\"avc1\"", 720, 8_000_000),
        video(247, "video/webm; codecs=\"vp9\"", 720, 6_500_000),
        video(137, "video/mp4; codecs=\"avc1\"", 1080, 15_000_000),
    )

    @Test
    fun best_audio_prefers_webm_opus_when_not_aac() {
        assertEquals(251, FormatSelection.bestAudio(formats, preferAac = false)?.itag)
    }

    @Test
    fun best_audio_prefers_mp4_aac_when_requested() {
        assertEquals(140, FormatSelection.bestAudio(formats, preferAac = true)?.itag)
    }

    @Test
    fun best_audio_skips_non_default_dub_track() {
        // itag 233 is a non-default (dubbed) AAC track; the default 140 must win even though both are mp4.
        assertEquals(140, FormatSelection.bestAudio(formats, preferAac = true)?.itag)
    }

    @Test
    fun video_nearest_picks_closest_height_for_webm() {
        // Request 720 → webm target prefers the vp9 720p (247) over the mp4 720p (136).
        assertEquals(247, FormatSelection.videoNearest(formats, 720, DownloadFormat.VIDEO_WEBM)?.itag)
    }

    @Test
    fun video_nearest_picks_mp4_codec_for_mp4_target() {
        assertEquals(136, FormatSelection.videoNearest(formats, 720, DownloadFormat.VIDEO_MP4)?.itag)
    }

    @Test
    fun video_nearest_null_height_picks_highest() {
        assertEquals(137, FormatSelection.videoNearest(formats, null, DownloadFormat.VIDEO_MP4)?.itag)
    }

    @Test
    fun no_audio_returns_null() {
        val onlyVideo = formats.filter { it.isVideo }
        assertNull(FormatSelection.bestAudio(onlyVideo, preferAac = false))
    }
}
