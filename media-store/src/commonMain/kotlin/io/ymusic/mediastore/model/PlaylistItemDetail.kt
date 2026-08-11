package io.ymusic.mediastore.model

/** A playlist membership row joined with the media item its [code] currently resolves to. */
data class PlaylistItemDetail(
    val playlistId: Long,
    val code: String,
    val addedAt: Long,
    val mediaItem: MediaStoreItem,
)
