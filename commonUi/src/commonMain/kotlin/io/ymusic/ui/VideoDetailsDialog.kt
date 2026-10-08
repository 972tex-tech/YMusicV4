package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtChip
import io.ymusic.dynamicengine.yt.YtComment
import io.ymusic.dynamicengine.yt.YtCommentPage
import io.ymusic.dynamicengine.yt.YtVideoDetails
import io.ymusic.dynamicengine.yt.createComment
import io.ymusic.dynamicengine.yt.extractComments
import io.ymusic.dynamicengine.yt.extractVideoDetails
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.show_less
import io.ymusic.ui.resources.show_more
import io.ymusic.ui.resources.video_details
import io.ymusic.ui.resources.video_details_comment_hint
import io.ymusic.ui.resources.video_details_comments
import io.ymusic.ui.resources.video_details_comments_off
import io.ymusic.ui.resources.video_details_hide_replies
import io.ymusic.ui.resources.video_details_more_replies
import io.ymusic.ui.resources.video_details_no_comments
import io.ymusic.ui.resources.video_details_post
import io.ymusic.ui.resources.video_details_posted
import io.ymusic.ui.resources.video_details_replies
import io.ymusic.ui.resources.video_details_sign_in_to_comment
import kmpcommon.error.CodedError
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** One comments list — a video's top-level comments, or one thread's replies. */
internal class CommentList {
    val comments = mutableStateListOf<YtComment>()
    var continuation by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var errorCode by mutableStateOf<String?>(null)
}

/** The watch page minus the player for [videoId]: details, then comments paged on demand. */
internal class VideoDetailsState(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    val videoId: String,
    parentLog: Logger?,
) {
    private val log = parentLog?.fork("VideoDetails", mapOf("videoId" to videoId))

    var details by mutableStateOf<YtVideoDetails?>(null)
    var detailsErrorCode by mutableStateOf<String?>(null)

    /** The first comments page's header (count, sort chips, composer). */
    var header by mutableStateOf<YtCommentPage?>(null)
    val comments = CommentList()
    val replies = mutableStateMapOf<String, CommentList>()
    var posting by mutableStateOf(false)
    var postErrorCode by mutableStateOf<String?>(null)

    fun load() {
        detailsErrorCode = null
        scope.launch {
            try {
                val loaded = graph.engines.extractVideoDetails(videoId, log)
                details = loaded
                loaded.commentsContinuation?.let { reloadComments(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "video details failed", error)
                detailsErrorCode = (error as? CodedError)?.code ?: "E20094"
            }
        }
    }

    /** Replace the list from [token] — the first page or a sort chip. */
    fun reloadComments(token: String) {
        comments.comments.clear()
        comments.continuation = token
        loadMore(comments, keepHeader = false)
    }

    fun loadMore(list: CommentList, keepHeader: Boolean = true) {
        val token = list.continuation ?: return
        if (list.loading) return
        list.loading = true
        list.errorCode = null
        scope.launch {
            try {
                val page = graph.engines.extractComments(token, log)
                if (!keepHeader && list === comments) header = page
                list.comments.addAll(page.comments)
                list.continuation = page.continuation
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "comments page failed", error)
                list.errorCode = (error as? CodedError)?.code ?: "E20095"
            } finally {
                list.loading = false
            }
        }
    }

    fun toggleReplies(comment: YtComment) {
        if (replies.remove(comment.id) != null) return
        val list = CommentList().apply { continuation = comment.repliesContinuation }
        replies[comment.id] = list
        loadMore(list)
    }

    fun post(text: String, onPosted: () -> Unit) {
        val token = header?.createToken ?: return
        if (posting) return
        posting = true
        postErrorCode = null
        scope.launch {
            try {
                val posted = graph.engines.createComment(token, text, log)
                comments.comments.add(0, posted)
                onPosted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "post comment failed", error)
                postErrorCode = (error as? CodedError)?.code ?: "E20096"
            } finally {
                posting = false
            }
        }
    }
}

/** Video details + comments in the app's [ElevatedBarDialog] chrome. */
@Composable
internal fun VideoDetailsDialog(
    graph: AppGraph,
    videoId: String,
    onOpenChannel: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(videoId) { VideoDetailsState(graph, scope, videoId, graph.logger) }
    LaunchedEffect(state) { state.load() }
    val listState = rememberLazyListState()
    ElevatedBarDialog(
        title = stringResource(Res.string.video_details),
        onDismiss = onDismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cd_close), color = DesignColors.Accent) }
        },
        maxWidth = 720.dp,
    ) {
        VideoDetailsContent(state, listState, onOpenChannel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VideoDetailsContent(
    state: VideoDetailsState,
    listState: LazyListState,
    onOpenChannel: (url: String, title: String) -> Unit,
    modifier: Modifier,
) {
    val details = state.details
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.comments.comments.size) {
        if (nearEnd && state.comments.errorCode == null) state.loadMore(state.comments)
    }
    LazyColumn(modifier, state = listState) {
        when {
            details != null -> item("details") { DetailsHeader(details, onOpenChannel) }
            state.detailsErrorCode != null -> item("error") {
                ErrorState(state.detailsErrorCode!!, Modifier.fillMaxWidth().heightIn(min = 240.dp)) { state.load() }
            }
            else -> item("loading") { Spinner(Modifier.heightIn(min = 240.dp)) }
        }
        if (details != null) commentsSection(state, details, onOpenChannel)
    }
}

@Composable
private fun DetailsHeader(details: YtVideoDetails, onOpenChannel: (url: String, title: String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        details.title?.let {
            Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong))
        }
        if (details.factoids.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                details.factoids.forEach { factoid ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(factoid.value, style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
                        Text(factoid.label, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                    }
                }
            }
        }
        details.channelTitle?.let { name ->
            val url = details.channelUrl
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (url != null) Modifier.clickable { onOpenChannel(url, name) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(details.channelThumbs, 40.dp)
                Column {
                    Text(name, style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink))
                    details.subscribers?.let {
                        Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                    }
                }
            }
        }
        details.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
        HorizontalDivider(color = DesignColors.Divider)
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DesignColors.SurfaceDim)
            .padding(DesignSpacing.Space16),
    ) {
        SelectionContainer {
            Text(
                text,
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
        }
        if (overflows || expanded) {
            Text(
                stringResource(if (expanded) Res.string.show_less else Res.string.show_more),
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                modifier = Modifier.padding(top = DesignSpacing.Space8).clickable { expanded = !expanded },
            )
        }
    }
}

private fun LazyListScope.commentsSection(
    state: VideoDetailsState,
    details: YtVideoDetails,
    onOpenChannel: (url: String, title: String) -> Unit,
) {
    item("comments-header") {
        val header = state.header
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Text(
                header?.countText ?: listOfNotNull(stringResource(Res.string.video_details_comments), details.commentsCount).joinToString(" "),
                style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong),
            )
            if (details.commentsContinuation == null) {
                Text(stringResource(Res.string.video_details_comments_off), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
            }
            if (header != null && header.sort.size > 1) SortChips(header.sort) { chip -> chip.token?.let { state.reloadComments(it) } }
            if (header != null) Composer(state, header)
        }
    }
    items(state.comments.comments, key = { "c:" + it.id }) { comment ->
        Column {
            CommentRow(comment, onOpenChannel, indent = false)
            if (comment.repliesContinuation != null) RepliesToggle(state, comment)
            state.replies[comment.id]?.let { list ->
                list.comments.forEach { reply -> CommentRow(reply, onOpenChannel, indent = true) }
                ListFooter(list, indent = true, moreLabel = stringResource(Res.string.video_details_more_replies)) { state.loadMore(list) }
            }
        }
    }
    item("comments-footer") {
        val list = state.comments
        when {
            list.loading || (list.errorCode == null && list.continuation != null) -> Spinner(Modifier.heightIn(min = 72.dp))
            list.errorCode != null -> ListFooter(list, indent = false, moreLabel = null) { state.loadMore(list) }
            state.header != null && list.comments.isEmpty() -> Text(
                stringResource(Res.string.video_details_no_comments),
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(DesignSpacing.Space16),
            )
        }
    }
}

@Composable
private fun SortChips(sort: List<YtChip>, onSelect: (YtChip) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        sort.forEach { chip ->
            FilterChip(selected = chip.selected, onClick = { if (!chip.selected) onSelect(chip) }, label = { Text(chip.title) })
        }
    }
}

@Composable
private fun Composer(state: VideoDetailsState, header: YtCommentPage) {
    if (header.createToken == null) {
        Text(stringResource(Res.string.video_details_sign_in_to_comment), style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
        return
    }
    var draft by remember { mutableStateOf("") }
    val snackbar = LocalSnackbar.current
    val posted = stringResource(Res.string.video_details_posted)
    val send = {
        val text = draft.trim()
        if (text.isNotEmpty()) state.post(text) { draft = ""; snackbar(posted) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppTextField(
            value = draft,
            onValueChange = { draft = it },
            label = header.composerPlaceholder ?: stringResource(Res.string.video_details_comment_hint),
            modifier = Modifier.weight(1f),
            singleLine = false,
            isError = state.postErrorCode != null,
            supportingText = state.postErrorCode?.let { errorMessage(it) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (state.posting) {
            CircularProgressIndicator(Modifier.padding(DesignSpacing.Space8).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = send, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(Res.string.video_details_post), tint = DesignColors.Accent)
            }
        }
    }
}

@Composable
private fun CommentRow(comment: YtComment, onOpenChannel: (url: String, title: String) -> Unit, indent: Boolean) {
    val author = comment.author
    val authorUrl = comment.authorChannelUrl
    val openAuthor = if (author != null && authorUrl != null) Modifier.clickable { onOpenChannel(authorUrl, author) } else Modifier
    Row(
        Modifier.fillMaxWidth().padding(
            start = if (indent) DesignSpacing.Space48 + DesignSpacing.Space16 else DesignSpacing.Space16,
            end = DesignSpacing.Space16,
            top = DesignSpacing.Space8,
            bottom = DesignSpacing.Space8,
        ),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
    ) {
        Box(openAuthor) { Avatar(comment.authorThumbs, if (indent) 24.dp else 32.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
            comment.pinned?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                    Icon(Icons.Outlined.PushPin, null, Modifier.size(12.dp), tint = DesignColors.InkSecondary)
                    Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary))
                }
            }
            Text(
                listOfNotNull(author, comment.published).joinToString(" · "),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontWeight = if (comment.byCreator) FontWeight.Bold else FontWeight.Normal,
                    color = DesignColors.InkSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = openAuthor,
            )
            SelectionContainer {
                Text(comment.text, style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = DesignColors.Ink))
            }
            if (comment.likes != null || comment.hearted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                    comment.likes?.let {
                        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = DesignColors.InkSecondary)
                        Text(it, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
                    }
                    if (comment.hearted) {
                        Icon(Icons.Filled.Favorite, null, Modifier.padding(start = DesignSpacing.Space8).size(14.dp), tint = DesignColors.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepliesToggle(state: VideoDetailsState, comment: YtComment) {
    val open = state.replies.containsKey(comment.id)
    Text(
        if (open) stringResource(Res.string.video_details_hide_replies) else stringResource(Res.string.video_details_replies, comment.replyCount.orEmpty()),
        style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier
            .padding(start = DesignSpacing.Space48 + DesignSpacing.Space16)
            .clip(RoundedCornerShape(999.dp))
            .clickable { state.toggleReplies(comment) }
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
    )
}

/** Under a list: spinner while a page loads, the error with a retry, or a "more" action. */
@Composable
private fun ListFooter(list: CommentList, indent: Boolean, moreLabel: String?, onMore: () -> Unit) {
    val start = if (indent) DesignSpacing.Space48 + DesignSpacing.Space16 else DesignSpacing.Space16
    when {
        list.loading -> Spinner(Modifier.padding(start = start).heightIn(min = 48.dp))
        list.errorCode != null -> Row(Modifier.padding(start = start), verticalAlignment = Alignment.CenterVertically) {
            Text(errorMessage(list.errorCode!!), style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
            TextButton(onClick = onMore) { Text(stringResource(Res.string.retry)) }
        }
        list.continuation != null && moreLabel != null -> TextButton(onClick = onMore, modifier = Modifier.padding(start = start)) {
            Text(moreLabel, color = DesignColors.Accent)
        }
    }
}

@Composable
private fun Avatar(thumbs: List<io.ymusic.dynamicengine.yt.YtThumb>, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        ThumbImage(thumbs, CircleShape) { ThumbPlaceholder(Icons.Outlined.Person, size / 2) }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}
