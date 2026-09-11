package kmpffmpeg.metadata

/**
 * One elementary stream of a media file. Ported from YMusic-legacy. The numeric fields are nullable
 * because `ffmpeg -i`'s text dump omits them for some containers/codecs (e.g. a raw FLAC stream prints
 * no per-stream bitrate); a null means "ffmpeg didn't report it", not "zero".
 */
sealed class StreamMetadata {
    /** The ffmpeg stream index within its input — the `N` of `Stream #0:N`, used as `-map 0:N`. NOT
     *  this stream's position in [MediaFileMetadata.streams] (that list drops Data/Attachment streams). */
    abstract val index: Int
    abstract val codec: String
    abstract val metadataMap: MetadataMap
    abstract val disposition: Set<String>

    data class Audio(
        override val index: Int,
        override val codec: String,
        val duration: Long?,
        val sample: Long?,
        val channels: Int?,
        val bitrate: Long?,
        override val metadataMap: MetadataMap,
        override val disposition: Set<String>
    ) : StreamMetadata()

    data class Video(
        override val index: Int,
        override val codec: String,
        val duration: Long?,
        val width: Int?,
        val height: Int?,
        /** Frames per second when ffmpeg printed `N fps`; null for stills / attached pics. */
        val fps: Double? = null,
        override val metadataMap: MetadataMap,
        override val disposition: Set<String>
    ) : StreamMetadata()

    data class Text(
        override val index: Int,
        override val codec: String,
        override val metadataMap: MetadataMap,
        override val disposition: Set<String>
    ) : StreamMetadata()

    companion object {
        const val DISPOSITION_DEFAULT = "default"
        const val DISPOSITION_ATTACHED_PIC = "attached_pic"
    }
}

fun StreamMetadata.isAttachedThumbnail(): Boolean {
    return this is StreamMetadata.Video &&
        (
            this.disposition.contains(StreamMetadata.DISPOSITION_ATTACHED_PIC) ||
                this.metadataMap.comment.orEmpty().startsWith("Cover")
            )
}
