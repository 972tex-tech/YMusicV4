package io.ymusic.download

import kotlinx.serialization.json.JsonObject
import projectcommon.json.objectOrNull
import projectcommon.json.stringOrNull

/** The track's artist from a `/player` response: YouTube Music's credited artists, else the channel name. */
internal object PlayerArtist {

    private const val MUSIC_DESCRIPTION_PREFIX = "Provided to YouTube by"
    private const val CREDIT_SEPARATOR = " · "
    private const val TOPIC_SUFFIX = " - Topic"
    private val officialSuffix = Regex("[^\\w,]+official\\b", RegexOption.IGNORE_CASE)

    fun parse(playerResponse: JsonObject): String? {
        val details = playerResponse.objectOrNull("videoDetails") ?: return null
        details.stringOrNull("shortDescription")?.let(::creditedArtists)?.let { return it }
        return details.stringOrNull("author")?.let(::channelArtist)
    }

    /**
     * Auto-generated music uploads describe themselves as "Provided to YouTube by <label>", then a
     * blank line, then `Title · Artist · Artist` — the only structured artist credit `/player` carries.
     */
    private fun creditedArtists(description: String): String? {
        if (!description.startsWith(MUSIC_DESCRIPTION_PREFIX)) return null
        val credit = description.lineSequence().firstOrNull { CREDIT_SEPARATOR in it } ?: return null
        return credit.split(CREDIT_SEPARATOR).drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(", ").ifEmpty { null }
    }

    private fun channelArtist(author: String): String? =
        officialSuffix.replace(author.removeSuffix(TOPIC_SUFFIX), "").trim().ifEmpty { null }
}
