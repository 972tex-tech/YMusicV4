package io.ymusic.mediastore.model

import kmpcommon.uri.Uri

/**
 * A bucket is a folder in the media hierarchy, identified externally by [uri] and internally by the
 * database-generated [id]. [parentId] is the enclosing bucket (null for a root bucket).
 */
data class MediaStoreBucket(
    val id: Long,
    val parentId: Long?,
    val uri: Uri,
    val name: String,
    val aliasName: String?,
    /** User-excluded: its songs are hidden from the library and rescans skip it, but it still shows
     *  (greyed) in the Folders tab so it can be re-included. Cascades to the whole subtree. */
    val excluded: Boolean,
    /** When this folder was first indexed into the store (epoch millis) — the "date added". */
    val createdAt: Long,
)
