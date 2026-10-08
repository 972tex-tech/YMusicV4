package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.ymusic.player.formatProgressClock
import io.ymusic.ui.playhistory.PlayAgainSession
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.menu_play_from_beginning
import io.ymusic.ui.resources.menu_remove
import io.ymusic.ui.resources.play_again_continue_at
import io.ymusic.ui.resources.play_again_empty
import kmpcommon.error.TechnicalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** "Play again" (legacy Rewind playlist): the queues played lately, newest first; a tap resumes one. */
@Composable
fun PlayAgainScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val store = graph.playHistory?.playAgain
    val log = remember { graph.logger?.fork("play-again") }
    val scope = rememberCoroutineScope()
    val showSnackbar = LocalSnackbar.current
    var sessions by remember { mutableStateOf<List<PlayAgainSession>?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    if (store == null) {
        EmptyState(stringResource(Res.string.play_again_empty), onReload = {})
        return
    }
    LaunchedEffect(store, reloads) {
        store.changes.onStart { emit(Unit) }.collectLatest {
            try {
                sessions = store.sessions()
                errorCode = null
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log?.captureError(TechnicalException("E60060", "play-again list failed to load", cause = e))
                errorCode = "E60060"
            }
        }
    }
    actionError?.let { code ->
        val message = errorMessage(code)
        LaunchedEffect(code, message) {
            showSnackbar(message)
            actionError = null
        }
    }

    fun runAction(session: PlayAgainSession, code: String, what: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log?.captureError(TechnicalException(code, what, cause = e), prop = mapOf("queueId" to session.queueId))
                actionError = code
            }
        }
    }

    fun restore(session: PlayAgainSession, fromBeginning: Boolean) =
        runAction(session, "E60061", "play-again queue failed to restore") {
            coordinator.playAgain(session, fromBeginning)
        }

    val code = errorCode
    val list = sessions
    when {
        code != null -> ErrorState(code) { reloads++ }
        list == null -> CenteredBox { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(stringResource(Res.string.play_again_empty), onReload = { reloads++ })
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.queueId }) { session ->
                PlayAgainRow(
                    session,
                    onPlay = { restore(session, fromBeginning = false) },
                    onPlayFromBeginning = { restore(session, fromBeginning = true) },
                    onRemove = {
                        runAction(session, "E60062", "play-again session failed to delete") {
                            store.delete(session.queueId)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayAgainRow(
    session: PlayAgainSession,
    onPlay: () -> Unit,
    onPlayFromBeginning: () -> Unit,
    onRemove: () -> Unit,
) {
    val fromBeginning = stringResource(Res.string.menu_play_from_beginning)
    val remove = stringResource(Res.string.menu_remove)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(
                start = DesignSpacing.Space16,
                end = DesignSpacing.Space4,
                top = DesignSpacing.Space8,
                bottom = DesignSpacing.Space8,
            ),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.Top,
    ) {
        LibraryThumb(
            Icons.AutoMirrored.Filled.QueueMusic,
            badge = "${session.queueIndex + 1}/${session.queueSize}",
            artUrl = session.thumbnailUrl,
        )
        Column(Modifier.weight(1f)) {
            Text(session.title, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    Res.string.play_again_continue_at,
                    formatProgressClock(if (session.resumesMidItem) session.positionMs else 0L),
                ),
                style = ListSubStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = DesignSpacing.Space4),
            )
        }
        MoreMenu(
            ItemActions(listOf(fromBeginning to onPlayFromBeginning, remove to onRemove)),
            Modifier.padding(top = DesignSpacing.Space2),
        )
    }
}
