package ymusic.mediastore.model

import kmpcommon.uri.Uri

/**
 * A single audio/video file with its extracted metadata. Identified externally by [uri] and
 * internally by the database-generated [id].
 *
 * [fileSize] and [lastModified] are the cheap change/move fingerprint: an unchanged size+mtime lets
 * a scanner skip re-reading the file, and a soft-deleted item whose [fileSize] reappears under a new
 * uri in the same bucket is treated as the *same* file moved (its [id] is preserved) rather than a
 * duplicate — see BetterMediaStore.upsertItem.
 */
data class MediaStoreItem(
    val id: Long,
    val bucketId: Long,
    val uri: Uri,
    val title: String,
    val mimeType: String,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val year: Int?,
    val durationMs: Long?,
    val audioCodec: String?,
    val videoCodec: String?,
    val subtitleCodec: String?,
    val bitrate: Int?,
    val width: Int?,
    val height: Int?,
    val thumbnailStreamIndex: Int?,
    val youtubeVid: String?,
    val youtubeItags: String?,
    val fileSize: Long,
    val lastModified: Long,
)
