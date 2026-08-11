package io.ymusic.mediascanner

import io.ymusic.mediastore.model.MediaItemDraft

/**
 * Turns a discovered media [DocumentInfo.File] into a [MediaItemDraft] by reading its tags/streams.
 * The real implementation is [FfmpegMetadataExtractor]; platforms without ffmpeg (iOS) use
 * [NoopMetadataExtractor].
 */
interface MediaMetadataExtractor {
    /** Extract a draft for [file]; return null if it cannot be probed (the scanner then skips it). */
    suspend fun extract(file: DocumentInfo.File): MediaItemDraft?
}

/** Extracts nothing — every file is skipped. Used where ffmpeg is unavailable (iOS). */
object NoopMetadataExtractor : MediaMetadataExtractor {
    override suspend fun extract(file: DocumentInfo.File): MediaItemDraft? = null
}
