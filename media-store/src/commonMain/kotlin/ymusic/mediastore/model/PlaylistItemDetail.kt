package ymusic.mediastore.model

/** A playlist membership row joined with the media item it points at. */
data class PlaylistItemDetail(
    val playlistItemId: Long,
    val playlistId: Long,
    val position: Int,
    val addedAt: Long,
    val mediaItem: MediaStoreItem,
)
