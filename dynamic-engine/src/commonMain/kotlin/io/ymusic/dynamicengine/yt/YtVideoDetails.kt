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
    val description: String? = null,
    val commentsCount: String? = null,
    /** The first [extractComments] token; null = comments are off. */
    val commentsContinuation: String? = null,
)

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
