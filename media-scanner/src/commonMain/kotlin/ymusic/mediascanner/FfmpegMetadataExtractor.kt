package ymusic.mediascanner

import kmpffmpeg.metadata.FfmpegMetadataGetter
import ymusic.mediascanner.internal.toMediaItemDraft
import ymusic.mediastore.model.MediaItemDraft

/**
 * A [MediaMetadataExtractor] backed by kmpFfmpeg. Probes each file with `ffmpeg -i` (via
 * [FfmpegMetadataGetter], which also resolves `content://` on Android) and maps the result to a
 * [MediaItemDraft]. Available on JVM / Android / native Linux+macOS; not usable on iOS (kmpFfmpeg
 * cannot spawn a process there — use [NoopMetadataExtractor]).
 */
class FfmpegMetadataExtractor(
    private val metadataGetter: FfmpegMetadataGetter,
    private val timeoutMs: Long = 3000,
) : MediaMetadataExtractor {
    override suspend fun extract(file: DocumentInfo.File): MediaItemDraft =
        metadataGetter.getMetadata(file.uri, timeoutMs).toMediaItemDraft(file)
}
