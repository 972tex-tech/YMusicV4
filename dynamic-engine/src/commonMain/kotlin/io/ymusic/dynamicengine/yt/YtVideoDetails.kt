package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngineManager
import kmpcommon.log.Logger

/** The watch page minus the player (`WireVideoDetails`); every field is engine-composed display text. */
data class YtVideoDetails(
    val title: String? = null,
    val channelTitle: String? = null,
    /** Engine-composed app link to the channel page. */
    val channelUrl: String? = null,
    val channelThumbs: List<YtThumb> = emptyList(),
    val subscribers: String? = null,
    val factoids: List<YtFactoid> = emptyList(),
    /** The like button's count, and its count once the viewer's own like is in. */
    val likeCount: String? = null,
    val likeCountLiked: String? = null,
    /** The viewer's rating, one of [LIKE] / [DISLIKE] / [INDIFFERENT]. */
    val likeStatus: String = INDIFFERENT,
    /** Engine-private [rateVideo] tokens keyed by the new status; empty = the viewer cannot rate
     *  (signed out). */
    val rateTokens: Map<String, String> = emptyMap(),
    val description: String? = null,
    val commentsCount: String? = null,
    /** The first [extractComments] token; null = comments are off. */
    val commentsContinuation: String? = null,
) {
    companion object {
        const val LIKE = "LIKE"
        const val DISLIKE = "DISLIKE"
        const val INDIFFERENT = "INDIFFERENT"
    }
}

/** A headline stat rendered as [value] over [label] ("19M" / "Likes"). */
data class YtFactoid(val value: String, val label: String)

data class YtComment(
    val id: String,
    val text: String,
    val author: String? = null,
    val authorThumbs: List<YtThumb> = emptyList(),
    /** Engine-composed app link to the author's channel. */
    val authorChannelUrl: String? = null,
    val published: String? = null,
    val likes: String? = null,
    /** [likes] once the viewer's own like is in. */
    val likesLiked: String? = null,
    val liked: Boolean = false,
    val disliked: Boolean = false,
    /** Engine-private [commentAction] tokens; null when the viewer cannot rate (signed out). */
    val likeToken: String? = null,
    val unlikeToken: String? = null,
    val dislikeToken: String? = null,
    val undislikeToken: String? = null,
    /** Engine-private tokens on the viewer's own comments only: [commentAction] deletes it,
     *  [editComment] replaces its text. */
    val deleteToken: String? = null,
    val editToken: String? = null,
    /** Engine-private [replyToComment] token; null when the viewer cannot reply. */
    val replyToken: String? = null,
    val replyPlaceholder: String? = null,
    val replyCount: String? = null,
    /** The [extractComments] token for this thread's replies. */
    val repliesContinuation: String? = null,
    /** "Pinned by @…". */
    val pinned: String? = null,
    val hearted: Boolean = false,
    val byCreator: Boolean = false,
    val verified: Boolean = false,
)

/** A page of comments or of one thread's replies. The header fields ride only a video's first
 *  comments page; a null [createToken] means this viewer cannot comment (signed out / disabled). */
data class YtCommentPage(
    val countText: String? = null,
    /** Top / Newest; each [YtChip.token] reloads the list via [extractComments]. */
    val sort: List<YtChip> = emptyList(),
    val composerPlaceholder: String? = null,
    val createToken: String? = null,
    val comments: List<YtComment> = emptyList(),
    val continuation: String? = null,
)

/** Title, channel, stats and description of [videoId], plus its comments entry token. */
suspend fun DynamicEngineManager.extractVideoDetails(videoId: String, logger: Logger? = null): YtVideoDetails =
    use { engine ->
        engine.jsonCommand(
            "yt/video-details",
            { w -> w.name("videoId").value(videoId) },
            logger.cmd("extractVideoDetails", "videoId" to videoId),
        ) { readVideoDetails(it) }
    }

/** A comments page behind [continuation]: [YtVideoDetails.commentsContinuation], a sort chip's
 *  token, a page's [YtCommentPage.continuation], or a thread's [YtComment.repliesContinuation]. */
suspend fun DynamicEngineManager.extractComments(continuation: String, logger: Logger? = null): YtCommentPage =
    use { engine ->
        engine.jsonCommand(
            "yt/comments",
            { w -> w.name("continuation").value(continuation) },
            logger.cmd("extractComments", "continuation" to continuation.crumbToken()),
        ) { readCommentPage(it) }
    }

/** Set the viewer's rating of [videoId] to [status] ([YtVideoDetails.LIKE] / DISLIKE / INDIFFERENT)
 *  with the details' [YtVideoDetails.rateTokens] entry for it. */
suspend fun DynamicEngineManager.rateVideo(videoId: String, status: String, token: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/video/rate",
            { w ->
                w.name("videoId").value(videoId)
                w.name("status").value(status)
                w.name("token").value(token)
            },
            logger.cmd("rateVideo", "videoId" to videoId, "status" to status),
        ) { it.skipValue() }
    }
}

/** Post [text] as a comment through a page's [YtCommentPage.createToken]; returns the posted comment. */
suspend fun DynamicEngineManager.createComment(createToken: String, text: String, logger: Logger? = null): YtComment =
    use { engine ->
        engine.jsonCommand(
            "yt/comment/create",
            { w ->
                w.name("token").value(createToken)
                w.name("text").value(text)
            },
            logger.cmd("createComment"),
        ) { readComment(it) }
    }

/** Run one of a comment's like / unlike / dislike / undislike / delete tokens. */
suspend fun DynamicEngineManager.commentAction(token: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/comment/action",
            { w -> w.name("token").value(token) },
            logger.cmd("commentAction"),
        ) { it.skipValue() }
    }
}

/** Reply [text] to a comment through its [YtComment.replyToken]; returns the posted reply. */
suspend fun DynamicEngineManager.replyToComment(replyToken: String, text: String, logger: Logger? = null): YtComment =
    use { engine ->
        engine.jsonCommand(
            "yt/comment/reply",
            { w ->
                w.name("token").value(replyToken)
                w.name("text").value(text)
            },
            logger.cmd("replyToComment"),
        ) { readComment(it) }
    }

/** Replace the text of the viewer's own comment through its [YtComment.editToken]. */
suspend fun DynamicEngineManager.editComment(editToken: String, text: String, logger: Logger? = null) {
    use { engine ->
        engine.jsonCommand(
            "yt/comment/edit",
            { w ->
                w.name("token").value(editToken)
                w.name("text").value(text)
            },
            logger.cmd("editComment"),
        ) { it.skipValue() }
    }
}
