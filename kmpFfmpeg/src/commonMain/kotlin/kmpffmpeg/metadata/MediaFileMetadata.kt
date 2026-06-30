package kmpffmpeg.metadata

import kmpcommon.uri.Uri

/**
 * The full metadata of one media file: its container plus every stream. Ported from YMusic-legacy;
 * the Android `android.net.Uri` field is replaced by the multiplatform [kmpcommon.uri.Uri]. [uri] is
 * the logical source this was probed from (e.g. `file:/music/song.flac` or a `ymix:` URL).
 */
data class MediaFileMetadata(
    val uri: Uri,
    val container: ContainerMetadata,
    val streams: List<StreamMetadata>
) {
    fun hasVideo(): Boolean {
        return streams.any {
            it is StreamMetadata.Video &&
                !it.isAttachedThumbnail()
        }
    }

    fun getMimeType(): String {
        // Container demuxer/muxer support of the trimmed prebuilt (D=demux E=mux):
        // D  aac      | DE flac | DE flv | D matroska,webm | DE mp3 |  E mp4
        // DE ogg      |  E opus | D mov,mp4,m4a,3gp,3g2,mj2 | D wav |  E webm | DE srt/webvtt
        return if (hasVideo()) {
            when {
                container.name.contains("webm") -> "video/webm"
                container.name.contains("mp4") -> "video/mp4"
                container.name.contains("flv") -> "video/x-flv"
                container.name.contains("avi") -> "video/x-msvideo"
                else -> "video/${container.name.substringBefore(",")}"
            }
        } else {
            when {
                container.name.contains("mp3") -> "audio/mpeg"
                container.name.contains("webm") -> "audio/webm"
                else -> "audio/${container.name.substringBefore(",")}"
            }
        }
    }
}
