package ymusic.mediastore.model

/**
 * A membership row linking a media item into a playlist at an explicit [position].
 * References the item by [mediaItemId] (kept stable across file moves via move-in-place).
 */
data class PlaylistItem(
    val id: Long,
    val playlistId: Long,
    val mediaItemId: Long,
    val position: Int,
    val addedAt: Long,
)
