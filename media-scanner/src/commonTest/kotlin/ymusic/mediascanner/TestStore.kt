package ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import ymusic.mediastore.model.MediaItemDraft

/** A fresh in-memory SQLite driver (schema created), closed by each test to keep tests isolated. */
internal expect fun newTestDriver(): SqlDriver

/** A deterministic extractor that derives a draft straight from the file stat — no ffmpeg. */
internal class FakeMetadataExtractor(
    private val failOn: Set<String> = emptySet(),
    private val timeoutOn: Set<String> = emptySet(),
) : MediaMetadataExtractor {
    override suspend fun extract(file: DocumentInfo.File): MediaItemDraft {
        if (file.name in failOn) throw IllegalStateException("boom: ${file.name}")
        // Simulate FfmpegMetadataGetter's post-fix behavior: a probe timeout surfaces as a plain
        // IOException, NOT a CancellationException (which the scanner would silently swallow).
        if (file.name in timeoutOn) throw okio.IOException("ffmpeg probe timed out: ${file.name}")
        return MediaItemDraft(
            uri = file.uri,
            title = file.name.substringBeforeLast('.'),
            mimeType = file.mimeType,
            fileSize = file.size,
            lastModified = file.lastModified,
        )
    }
}
