package io.ymusic.mediastore.model

/**
 * A playlist membership row. Identified by [playlistId] + [code] (a content fingerprint, see
 * [MediaStoreItem.playlistCode]) — not by a media-item row id, so it survives moves/rescans.
 * [sortKey] orders items within the playlist.
 */
data class PlaylistItem(
    val playlistId: Long,
    val code: String,
    val sortKey: Long,
    val addedAt: Long,
)
