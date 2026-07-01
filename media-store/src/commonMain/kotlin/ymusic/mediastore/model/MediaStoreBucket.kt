package ymusic.mediastore.model

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
)
