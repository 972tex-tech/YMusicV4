package io.ymusic.download

import io.ymusic.ytmedia.sabr.AdaptiveFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import projectcommon.error.CodedError
import projectcommon.error.TechnicalException
import projectcommon.json.boolOrNull
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/** The video is a live broadcast in progress: there is no finished file to download until it ends. A
 *  well-known category ([code] `"live"`) the UI renders as its own message, not an error code. */
class LiveVideoException(videoId: String?) : Exception("live video ${videoId.orEmpty()}"), CodedError {
    override val code: String = CODE
    override val userMessage: String? = null

    companion object {
        const val CODE = "live"
    }
}

/** One downloadable video height; sizes are estimates for each container (video + its audio track). */
class VideoOption(val height: Int, val fps: Int, val mp4Bytes: Long, val webmBytes: Long)

/** One audio-only output and its estimated size. */
class AudioOption(val format: DownloadFormat, val bytes: Long)

/** One audio language of a video that has dubs. */
class AudioTrackOption(val id: String, val name: String, val isDefault: Boolean)

/** One caption track a download can fetch. */
class CaptionOption(
    val languageCode: String,
    val name: String,
    val isAsr: Boolean,
    val isDefault: Boolean,
    /** One of YouTube's auto-translations of the video's own track. */
    val isTranslated: Boolean = false,
) {
    val key: String get() = captionKey(languageCode, isAsr)
}

/**
 * What one video can be downloaded as, read from its player response — the input of the download options
 * dialog. Sizes are estimates: stream-copied tracks use their `contentLength`, transcoded audio uses the
 * encoder's target bitrate over the duration.
 */
class DownloadOptions(
    val durationMs: Long,
    /** Highest first. */
    val video: List<VideoOption>,
    val audio: List<AudioOption>,
    /** Empty unless the video carries more than one audio language. */
    val audioTracks: List<AudioTrackOption>,
    val captions: List<CaptionOption>,
) {
    fun audioBytes(format: DownloadFormat): Long? = audio.firstOrNull { it.format == format }?.bytes

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private const val AAC_TRANSCODE_BPS = 192_000L
        private const val OPUS_TRANSCODE_BPS = 160_000L
        private const val MP3_VBR_BPS = 190_000L

        fun parse(playerResponseJson: String, ffmpegAvailable: Boolean): DownloadOptions {
            val data = json.parseToJsonElement(playerResponseJson).jsonObject
            val details = data.objectOrNull("videoDetails")
            if (details?.boolOrNull("isLive") == true) throw LiveVideoException(details.stringOrNull("videoId"))
            val streaming = data.objectOrNull("streamingData")
                ?: throw TechnicalException("E70041", "download options: no streamingData")
            val formats = SabrSpecBuilder.adaptiveFormats(streaming)
            val raw = streaming.objects("adaptiveFormats")
            val durationMs = formats.maxOfOrNull { it.approxDurationMs.toLong() }?.takeIf { it > 0 }
                ?: (details?.longOrNull("lengthSeconds") ?: 0L) * 1000
            if (formats.none { it.mimeType.startsWith("audio") && it.clen > 0 }) {
                throw TechnicalException("E70042", "download options: no audio format")
            }
            return DownloadOptions(
                durationMs = durationMs,
                video = if (ffmpegAvailable) videoOptions(formats, raw, durationMs) else emptyList(),
                audio = audioOptions(formats, durationMs, ffmpegAvailable),
                audioTracks = audioTracks(raw),
                captions = captionEntries(data).map { CaptionOption(it.languageCode, it.name, it.isAsr, it.isDefault, it.isTranslated) },
            )
        }

        private fun videoOptions(formats: List<AdaptiveFormat>, raw: List<JsonObject>, durationMs: Long): List<VideoOption> {
            val fpsByItag = raw.associate { (it.intOrNull("itag") ?: -1) to (it.intOrNull("fps") ?: 0) }
            val heights = formats.filter { it.isVideo && it.clen > 0 }.mapNotNull { it.height }.distinct().sortedDescending()
            val opus = FormatSelection.bestAudio(formats, preferAac = false)
            val webmAudioBytes = if (opus?.mimeType?.contains("webm") == true) opus.clen else bytesAt(OPUS_TRANSCODE_BPS, durationMs)
            return heights.map { height ->
                val atHeight = formats.filter { it.isVideo && it.height == height }
                val mp4Video = FormatSelection.videoNearest(atHeight, height, DownloadFormat.VIDEO_MP4)
                val webmVideo = FormatSelection.videoNearest(atHeight, height, DownloadFormat.VIDEO_WEBM)
                VideoOption(
                    height = height,
                    fps = atHeight.maxOfOrNull { fpsByItag[it.itag] ?: 0 } ?: 0,
                    mp4Bytes = (mp4Video?.clen ?: 0) + bytesAt(AAC_TRANSCODE_BPS, durationMs),
                    webmBytes = (webmVideo?.clen ?: 0) + webmAudioBytes,
                )
            }
        }

        private fun audioOptions(formats: List<AdaptiveFormat>, durationMs: Long, ffmpegAvailable: Boolean): List<AudioOption> {
            val aac = FormatSelection.bestAudio(formats, preferAac = true)
            val opus = FormatSelection.bestAudio(formats, preferAac = false)
            val m4a = AudioOption(
                DownloadFormat.M4A,
                if (aac?.mimeType?.contains("mp4") == true) aac.clen else bytesAt(AAC_TRANSCODE_BPS, durationMs),
            )
            if (!ffmpegAvailable) return listOf(m4a)
            return listOf(
                AudioOption(
                    DownloadFormat.AUDIO_WEBM,
                    if (opus?.mimeType?.contains("webm") == true) opus.clen else bytesAt(OPUS_TRANSCODE_BPS, durationMs),
                ),
                m4a,
                AudioOption(DownloadFormat.MP3, bytesAt(MP3_VBR_BPS, durationMs)),
            )
        }

        private fun audioTracks(raw: List<JsonObject>): List<AudioTrackOption> {
            val tracks = raw.mapNotNull { fmt ->
                val track = fmt.objectOrNull("audioTrack") ?: return@mapNotNull null
                val id = track.stringOrNull("id") ?: return@mapNotNull null
                AudioTrackOption(id, track.stringOrNull("displayName") ?: id, track.boolOrNull("audioIsDefault") ?: false)
            }.distinctBy { it.id }
            return if (tracks.size > 1) tracks.sortedByDescending { it.isDefault } else emptyList()
        }

        private fun bytesAt(bitsPerSecond: Long, durationMs: Long): Long = bitsPerSecond / 8 * durationMs / 1000
    }
}
