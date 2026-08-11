package io.ymusic.mediastore.model

data class Playlist(
    val id: Long,
    val name: String,
    /** When the playlist was created (epoch millis). */
    val createdAt: Long,
)
