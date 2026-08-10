@file:androidx.annotation.OptIn(UnstableApi::class)

package ymusic.player.media3

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import ymusic.player.LoopMode
import ymusic.player.PlayableItem
import ymusic.player.PlaybackState
import ymusic.player.Player
import kotlin.math.abs
import androidx.media3.common.Player as Media3Player

/**
 * Adapts the [Player] facade to a media3 `Player` (via [SimpleBasePlayer]) so a media3
 * `MediaSession`/`MediaSessionService` can drive it — notification, media buttons, lock screen,
 * Bluetooth/Auto transport all come from media3 reading this adapter. State flows one way (facade
 * flows → [invalidateState]); commands flow back onto the facade's own transport methods, so the
 * session and the in-app UI always agree.
 *
 * Never releases the shared facade: [handleRelease] only stops mirroring. Main-thread bound, like
 * the facade it wraps.
 *
 * [describe] supplies per-item display metadata (the queue holds only [PlayableItem]s);
 * [extraInvalidations] re-snapshots when an external metadata source updates (e.g. titles arriving
 * after the queue was set).
 */
class Media3PlayerAdapter(
    private val player: Player,
    private val describe: (PlayableItem) -> MediaMetadata,
    extraInvalidations: Flow<*> = emptyFlow<Unit>(),
    /** Applies a controller's setMediaItems (an Android Auto browse tap arrives this way after the
     *  session callback resolved the ids) onto the facade — the owner decides how a mediaId becomes
     *  a queue (context expansion, watch-next autoplay). Null = the command is advertised but a
     *  set is ignored (in-app UIs drive the facade directly). */
    private val applyMediaItems: ((items: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit)? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        var lastPositionMs = 0L
        var lastDurationMs = Long.MIN_VALUE
        scope.launch {
            merge(
                player.status,
                player.queue,
                player.config,
                // The play-head is read live via PositionSupplier — a tick needs no invalidation.
                // A seek (position jump) or a newly-discovered duration re-anchors the snapshot.
                player.progress.mapNotNull { p ->
                    val jumped = abs(p.positionMs - lastPositionMs) > POSITION_JUMP_MS
                    val durationChanged = p.durationMs != lastDurationMs
                    lastPositionMs = p.positionMs
                    lastDurationMs = p.durationMs
                    if (jumped || durationChanged) Unit else null
                },
                extraInvalidations,
            ).collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val status = player.status.value
        val queue = player.queue.value
        val items = queue.items
        val prepared = status.state as? PlaybackState.Prepared
        val error = (status.state as? PlaybackState.Error)?.error

        val playbackState = when {
            items.isEmpty() || error != null || prepared == null -> Media3Player.STATE_IDLE
            prepared.ended -> Media3Player.STATE_ENDED
            prepared.buffering -> Media3Player.STATE_BUFFERING
            else -> Media3Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(prepared?.playWhenReady == true, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(playbackState == Media3Player.STATE_BUFFERING)
            .setRepeatMode(
                when (queue.loopMode) {
                    LoopMode.None -> Media3Player.REPEAT_MODE_OFF
                    LoopMode.One -> Media3Player.REPEAT_MODE_ONE
                    LoopMode.All -> Media3Player.REPEAT_MODE_ALL
                },
            )
            .setShuffleModeEnabled(queue.shuffle)
            .setVolume(status.volume.coerceIn(0f, 1f))
            .setPlaybackParameters(
                if (status.speed == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(status.speed),
            )
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)

        if (error != null) {
            builder.setPlayerError(
                PlaybackException(
                    listOfNotNull(error.code, error.message).joinToString(": "),
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                ),
            )
        }

        if (items.isNotEmpty()) {
            val currentIndex = queue.currentIndex.coerceIn(0, items.lastIndex)
            val durationMs = player.progress.value.durationMs
            builder.setPlaylist(
                items.mapIndexed { i, item ->
                    val key = item.mediaKey
                    MediaItemData.Builder("$i:$key")
                        .setMediaItem(
                            MediaItem.Builder().setMediaId(key).setMediaMetadata(describe(item)).build(),
                        )
                        .setDurationUs(
                            if (i == currentIndex && durationMs > 0) durationMs * 1000 else C.TIME_UNSET,
                        )
                        .setIsSeekable(true)
                        .build()
                },
            )
            builder.setCurrentMediaItemIndex(currentIndex)
            builder.setContentPositionMs { player.progress.value.positionMs }
            builder.setContentBufferedPositionMs { player.progress.value.bufferedPositionMs }
            builder.setTotalBufferedDurationMs {
                (player.progress.value.bufferedPositionMs - player.progress.value.positionMs).coerceAtLeast(0L)
            }
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = done {
        if (playWhenReady) player.play() else player.pause()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = done {
        applyMediaItems?.invoke(mediaItems, startIndex, startPositionMs)
    }

    override fun handlePrepare(): ListenableFuture<*> = done { player.play() }

    override fun handleStop(): ListenableFuture<*> = done { player.stop() }

    override fun handleRelease(): ListenableFuture<*> = done { scope.cancel() }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = done {
        when (seekCommand) {
            Media3Player.COMMAND_SEEK_TO_NEXT, Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.skipNext()
            Media3Player.COMMAND_SEEK_TO_PREVIOUS, Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                player.skipPrev()
            Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != player.queue.value.currentIndex) {
                    player.playAt(mediaItemIndex)
                } else if (positionMs != C.TIME_UNSET) {
                    player.seekTo(positionMs)
                }
            else -> player.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = done {
        player.setShuffle(shuffleModeEnabled)
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = done {
        player.setLoopMode(
            when (repeatMode) {
                Media3Player.REPEAT_MODE_ONE -> LoopMode.One
                Media3Player.REPEAT_MODE_ALL -> LoopMode.All
                else -> LoopMode.None
            },
        )
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> = done {
        player.setVolume(volume.coerceIn(0f, 1f))
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = done {
        player.setSpeed(playbackParameters.speed)
    }

    private inline fun done(block: () -> Unit): ListenableFuture<*> {
        block()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        const val POSITION_JUMP_MS = 2_000L

        val COMMANDS: Media3Player.Commands = Media3Player.Commands.Builder()
            .addAll(
                Media3Player.COMMAND_PLAY_PAUSE,
                Media3Player.COMMAND_PREPARE,
                Media3Player.COMMAND_STOP,
                Media3Player.COMMAND_SET_MEDIA_ITEM,
                Media3Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Media3Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_NEXT,
                Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_BACK,
                Media3Player.COMMAND_SEEK_FORWARD,
                Media3Player.COMMAND_SET_SPEED_AND_PITCH,
                Media3Player.COMMAND_SET_SHUFFLE_MODE,
                Media3Player.COMMAND_SET_REPEAT_MODE,
                Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_GET_TIMELINE,
                Media3Player.COMMAND_GET_METADATA,
                Media3Player.COMMAND_GET_VOLUME,
                Media3Player.COMMAND_SET_VOLUME,
                Media3Player.COMMAND_RELEASE,
            )
            .build()
    }
}

/** The queue key for a [PlayableItem] — stable identity for media3 timeline items. */
val PlayableItem.mediaKey: String
    get() = when (this) {
        is PlayableItem.YtMedia -> videoId
        is PlayableItem.LocalMedia -> uri
    }
