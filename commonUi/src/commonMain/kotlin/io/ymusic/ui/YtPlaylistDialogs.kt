package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtPlaylistTarget
import io.ymusic.dynamicengine.yt.addToPlaylist
import io.ymusic.dynamicengine.yt.createPlaylist
import io.ymusic.dynamicengine.yt.playlistTargets
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.add_to_playlist_new
import io.ymusic.ui.resources.cd_add_to_playlist
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.playlist_create_confirm
import io.ymusic.ui.resources.playlist_create_title
import io.ymusic.ui.resources.playlist_name_hint
import io.ymusic.ui.resources.yt_playlist_added
import io.ymusic.ui.resources.yt_playlist_created
import io.ymusic.ui.resources.yt_playlist_from_queue
import io.ymusic.ui.resources.yt_playlist_privacy_private
import io.ymusic.ui.resources.yt_playlist_privacy_public
import io.ymusic.ui.resources.yt_playlist_privacy_unlisted
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.errorCode

/** Opens the YouTube-playlist flows over the app shell; null outside it. Signed out, each explains
 *  that it needs sign-in instead of opening. */
class YtPlaylistActions(
    /** Pick one of the viewer's playlists (or a new one) for [videoId]. */
    val addToPlaylist: (videoId: String) -> Unit,
    /** Create a new playlist holding [videoIds] (the queue's YouTube videos). */
    val saveAsPlaylist: (videoIds: List<String>) -> Unit,
)

val LocalYtPlaylistActions = staticCompositionLocalOf<YtPlaylistActions?> { null }

/** What [YtPlaylistHost] is showing. */
sealed interface YtPlaylistRequest {
    data class Add(val videoId: String) : YtPlaylistRequest

    data class Create(val videoIds: List<String>, val fromQueue: Boolean) : YtPlaylistRequest
}

@Composable
internal fun YtPlaylistHost(graph: AppGraph, request: YtPlaylistRequest?, onDone: () -> Unit) {
    var current by remember(request) { mutableStateOf(request) }
    val log = remember(graph) { graph.logger?.fork("YtPlaylist") }
    when (val shown = current) {
        null -> Unit
        is YtPlaylistRequest.Add -> AddToYtPlaylistDialog(
            graph,
            shown.videoId,
            log,
            onCreate = { current = YtPlaylistRequest.Create(listOf(shown.videoId), fromQueue = false) },
            onDismiss = onDone,
        )
        is YtPlaylistRequest.Create -> CreateYtPlaylistDialog(graph, shown, log, onDismiss = onDone)
    }
}

/** The viewer's playlists plus "New playlist…"; a playlist already holding the video is checked and
 *  inert. Picking one adds the video (spinner on that row), then closes with a snackbar. */
@Composable
private fun AddToYtPlaylistDialog(
    graph: AppGraph,
    videoId: String,
    log: Logger?,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var targets by remember { mutableStateOf<List<YtPlaylistTarget>?>(null) }
    var loadErrorCode by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var addResult by remember { mutableStateOf<Pair<YtPlaylistTarget, Throwable?>?>(null) }
    LaunchedEffect(videoId) {
        try {
            targets = graph.engines.playlistTargets(videoId, log)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "playlist targets failed", error)
            loadErrorCode = error.errorCode() ?: "E20108"
        }
    }
    addResult?.let { (target, error) ->
        val message = if (error == null) {
            stringResource(Res.string.yt_playlist_added, target.title)
        } else {
            errorMessage(error.errorCode() ?: "E20109")
        }
        LaunchedEffect(addResult) {
            snackbar(message)
            addResult = null
            if (error == null) onDismiss()
        }
    }

    val listState = rememberLazyListState()
    val dismiss = { if (adding == null) onDismiss() }
    ElevatedBarDialog(
        title = stringResource(Res.string.cd_add_to_playlist),
        onDismiss = dismiss,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        buttons = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = adding == null, onClick = dismiss) },
    ) {
        val loaded = targets
        val failed = loadErrorCode
        when {
            failed != null -> Text(
                errorMessage(failed),
                style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            loaded == null -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = DesignColors.Accent)
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                item("new") {
                    YtPlaylistRow(Icons.Default.Add, stringResource(Res.string.add_to_playlist_new), DesignColors.Accent, busy = false) {
                        if (adding == null) onCreate()
                    }
                }
                items(loaded, key = { it.playlistId }) { target ->
                    YtPlaylistRow(
                        if (target.containsVideo) Icons.Default.Check else Icons.AutoMirrored.Filled.PlaylistPlay,
                        target.title,
                        if (target.containsVideo) DesignColors.InkFaint else DesignColors.Ink,
                        busy = adding == target.playlistId,
                    ) {
                        if (adding != null || target.containsVideo) return@YtPlaylistRow
                        adding = target.playlistId
                        scope.launch {
                            try {
                                graph.engines.addToPlaylist(target.playlistId, videoId, log)
                                addResult = target to null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                log?.log(Logger.Level.WARN, "add to playlist failed", error)
                                addResult = target to error
                            } finally {
                                adding = null
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YtPlaylistRow(icon: ImageVector, label: String, color: Color, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Text(
            label,
            style = TextStyle(fontSize = 14.sp, color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
    }
}

private val PrivacyOptions = listOf(
    YtPlaylistTarget.PRIVATE to Res.string.yt_playlist_privacy_private,
    YtPlaylistTarget.UNLISTED to Res.string.yt_playlist_privacy_unlisted,
    YtPlaylistTarget.PUBLIC to Res.string.yt_playlist_privacy_public,
)

/** Name + privacy for a new YouTube playlist holding the request's videos; closes with a snackbar
 *  once created, stays open (snackbar) on failure. */
@Composable
private fun CreateYtPlaylistDialog(graph: AppGraph, request: YtPlaylistRequest.Create, log: Logger?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(YtPlaylistTarget.PRIVATE) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val trimmed = name.trim()
    val canSubmit = !busy && trimmed.isNotEmpty()
    result?.let { done ->
        val message = done.fold(
            onSuccess = { stringResource(Res.string.yt_playlist_created, it) },
            onFailure = { errorMessage(it.errorCode() ?: "E20110") },
        )
        LaunchedEffect(done) {
            snackbar(message)
            result = null
            if (done.isSuccess) onDismiss()
        }
    }
    val dismiss = { if (!busy) onDismiss() }
    val submit = {
        if (canSubmit) {
            busy = true
            scope.launch {
                try {
                    graph.engines.createPlaylist(trimmed, privacy, request.videoIds, log)
                    result = Result.success(trimmed)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log?.log(Logger.Level.WARN, "create playlist failed", error)
                    result = Result.failure(error)
                } finally {
                    busy = false
                }
            }
        }
        Unit
    }
    ElevatedBarDialog(
        title = stringResource(Res.string.playlist_create_title),
        onDismiss = dismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), enabled = !busy, onClick = dismiss)
            if (busy) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
                }
            } else {
                TextButton(enabled = canSubmit, onClick = submit) {
                    Text(stringResource(Res.string.playlist_create_confirm), color = DesignColors.Accent)
                }
            }
        },
        dismissOnClickOutside = false,
    ) {
        Column {
            if (request.fromQueue) {
                Text(
                    stringResource(Res.string.yt_playlist_from_queue, request.videoIds.size),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
                )
            }
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.playlist_name_hint),
                autoFocus = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrivacyOptions.forEach { (value, label) ->
                    LibraryChip(stringResource(label), selected = privacy == value) { if (!busy) privacy = value }
                }
            }
        }
    }
}
