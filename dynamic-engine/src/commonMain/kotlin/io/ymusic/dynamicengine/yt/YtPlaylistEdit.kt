package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngineManager
import kmpcommon.log.Logger

/** One of the signed-in viewer's own playlists, as the add-to-playlist picker lists it. */
data class YtPlaylistTarget(
    val playlistId: String,
    val title: String,
    /** PUBLIC | UNLISTED | PRIVATE; null when YouTube didn't say. */
    val privacy: String?,
    /** The playlist already holds the video the picker was opened for. */
    val containsVideo: Boolean,
) {
    companion object {
        const val PUBLIC = "PUBLIC"
        const val UNLISTED = "UNLISTED"
        const val PRIVATE = "PRIVATE"
    }
}

/** The viewer's playlists [videoId] can be added to (Watch later included). */
suspend fun DynamicEngineManager.playlistTargets(videoId: String, logger: Logger? = null): List<YtPlaylistTarget> =
    use { engine ->
        engine.jsonCommand(
            "yt/playlist/targets",
            log = logger.cmd("playlistTargets", "videoId" to videoId),
            writeFields = { w -> w.name("videoId").value(videoId) },
        ) { readPlaylistTargets(it) }
    }

/** Append [videoId] to the viewer's playlist [playlistId]. */
suspend fun DynamicEngineManager.addToPlaylist(playlistId: String, videoId: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/playlist/add",
            log = logger.cmd("addToPlaylist", "playlistId" to playlistId, "videoId" to videoId),
            writeFields = { w ->
                w.name("playlistId").value(playlistId)
                w.name("videoId").value(videoId)
            },
        ) { it.skipValue() }
    }
}

/** Remove a video from the viewer's playlist through its [YtFeedItem.removeToken]. */
suspend fun DynamicEngineManager.removeFromPlaylist(removeToken: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/playlist/remove",
            log = logger.cmd("removeFromPlaylist"),
            writeFields = { w -> w.name("token").value(removeToken) },
        ) { it.skipValue() }
    }
}

/** Create a playlist named [title] ([privacy] one of [YtPlaylistTarget.PUBLIC]…) holding [videoIds];
 *  returns its id. */
suspend fun DynamicEngineManager.createPlaylist(
    title: String,
    privacy: String,
    videoIds: List<String>,
    logger: Logger? = null,
): String = use { engine ->
    engine.jsonCommand(
        "yt/playlist/create",
        log = logger.cmd("createPlaylist", "privacy" to privacy, "videos" to videoIds.size),
        writeFields = { w ->
            w.name("title").value(title)
            w.name("privacy").value(privacy)
            w.name("videoIds").beginArray()
            videoIds.forEach { w.value(it) }
            w.endArray()
        },
    ) { readCreatedPlaylistId(it) }
}
