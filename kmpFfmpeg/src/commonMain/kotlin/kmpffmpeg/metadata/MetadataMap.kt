package kmpffmpeg.metadata

/**
 * A case-insensitive bag of container/stream tags (`title`, `artist`, …) as printed by `ffmpeg -i`.
 * Ported from YMusic-legacy unchanged except for the package: the accessors pick the first key that
 * matches a known synonym list, so callers don't care whether a Matroska file spells it `ARTIST` or an
 * MP4 spells it `artist`.
 */
@Suppress("MemberVisibilityCanBePrivate")
data class MetadataMap(
    val all: Map<String, String?>,
) {
    companion object {
        val EMPTY = MetadataMap(emptyMap())

        val TITLE_KEYS = arrayOf("title", "title-sort")
        val ARTIST_KEYS = arrayOf("artist", "author", "album_artist", "performer", "artist-sort")
        val ALBUM_KEYS = arrayOf("album", "album-sort", "show")
        val GENRE_KEYS = arrayOf("genre")
        val YEAR_KEYS = arrayOf("year", "date")
        val COMPOSER_KEYS = arrayOf("composer")
        val TRACK_KEYS = arrayOf("track")
        val LANGUAGE_KEYS = arrayOf("language", "lang")
        val COMMENT_KEYS = arrayOf("comment")

        const val LYRICS_KEY_PREFIX = "lyrics"
    }

    val titleKey by lazy { findKey(TITLE_KEYS) }
    val artistKey by lazy { findKey(ARTIST_KEYS) }
    val albumKey by lazy { findKey(ALBUM_KEYS) }
    val genreKey by lazy { findKey(GENRE_KEYS) }
    val yearKey by lazy { findKey(YEAR_KEYS) }
    val composerKey by lazy { findKey(COMPOSER_KEYS) }
    val trackKey by lazy { findKey(TRACK_KEYS) }
    val languageKey by lazy { findKey(LANGUAGE_KEYS) }
    val commentKey by lazy { findKey(COMMENT_KEYS) }

    val title get() = all[titleKey]
    val artist get() = all[artistKey]
    val album get() = all[albumKey]
    val genre get() = all[genreKey]
    val year get() = all[yearKey]
    val composer get() = all[composerKey]
    val track get() = all[trackKey]
    val language get() = all[languageKey]
    val comment get() = all[commentKey]

    val lyrics by lazy {
        all.filter { (key, _) ->
            key.lowercase().startsWith(LYRICS_KEY_PREFIX)
        }
    }

    private fun findKey(keyList: Array<String>): String? {
        val lowercaseKeyList = keyList.map { it.lowercase() }
        return all.keys.firstOrNull { key ->
            key.lowercase() in lowercaseKeyList
        }
    }
}
