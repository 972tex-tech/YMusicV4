package io.ymusic.mediastore.model

/** A playlist as plain data, for restoring from a backup or importing from another app. */
data class PlaylistSnapshot(
    val name: String,
    val createdAt: Long,
    /** In playlist order. */
    val items: List<Item>,
) {
    data class Item(val code: String, val addedAt: Long)
}
