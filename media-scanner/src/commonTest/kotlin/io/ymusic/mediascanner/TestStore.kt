package io.ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediastore.model.MediaItemDraft

/** A fresh in-memory SQLite driver (schema created), closed by each test to keep tests isolated. */
internal expect fun newTestDriver(): SqlDriver

/** A deterministic extractor that derives a draft straight from the file stat — no ffmpeg. */
internal class FakeMetadataExtractor(
    private val failOn: Set<String> = emptySet(),
    private val timeoutOn: Set<String> = emptySet(),
) : MediaMetadataExtractor {
    override suspend fun extract(file: DocumentInfo.File): MediaItemDraft {
        if (file.name in failOn) throw IllegalStateException("boom: ${file.name}")
        // A probe timeout surfaces as IOException (see FfmpegMetadataGetter), not a CE.
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
