package io.ymusic.android

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The session's player until the app graph exists, so [PlaybackService] can build its session in
 * onCreate: media3 answers a legacy MediaBrowserService bind (Android Auto) from onGetSession once,
 * and the framework caches a null binder for the whole binding. Swapped for the real adapter via
 * `session.player = …`; play / setMediaItems in the gap are handed to the service to replay.
 */
@OptIn(UnstableApi::class)
internal class PendingPlayer(
    private val onPlay: () -> Unit,
    private val onSetItems: (items: List<MediaItem>, startIndex: Int) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            ).build(),
        )
        .setPlaybackState(Player.STATE_IDLE)
        .build()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        onSetItems(mediaItems.toList(), startIndex)
        return Futures.immediateVoidFuture()
    }

    // Advertised so media3 accepts multi-item sets; there is no queue to edit yet.
    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
