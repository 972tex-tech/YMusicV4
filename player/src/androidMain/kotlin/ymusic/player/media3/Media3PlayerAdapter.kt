@file:androidx.annotation.OptIn(UnstableApi::class)

package ymusic.player.media3

import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ymusic.player.LoopMode
import ymusic.player.PlayableItem
import ymusic.player.PlaybackState
import ymusic.player.Player
import ymusic.player.isPlaying
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
     *  session callback resolved the ids) onto the passed facade — the owner decides how a mediaId
     *  becomes a queue (context expansion, watch-next autoplay). Null = the command is advertised
     *  but a set is ignored (in-app UIs drive the facade directly). */
    private val applyMediaItems: ((player: Player, items: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit)? = null,
    /** Inserts controller-added items (addMediaItems / legacy addQueueItem) into the passed
     *  facade's queue at [MediaItem]-timeline position `index`. The items arrive pre-resolved by
     *  the session callback's onAddMediaItems, so the owner converts them back to [PlayableItem]s.
     *  Null = adds are dropped. */
    private val insertMediaItems: ((player: Player, index: Int, items: List<MediaItem>) -> Unit)? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        var prevPositionMs = 0L
        var prevAtMs = SystemClock.elapsedRealtime()
        var prevDurationMs = Long.MIN_VALUE
        scope.launch {
            merge(
                player.status,
                player.queue,
                player.config,
                // The play-head is read live via PositionSupplier — a tick needs no invalidation.
                // A seek re-anchors the snapshot, judged against the WALL CLOCK, not the previous
                // tick: while playing, position is expected to advance by elapsed×speed, so slow
                // or throttled ticks (screen-off WebView timers) don't masquerade as seeks. A
                // duration re-anchors only when it meaningfully changes (appears, or moves >1s).
                player.progress.mapNotNull { p ->
                    val now = SystemClock.elapsedRealtime()
                    val status = player.status.value
                    val expectedAdvance =
                        if (status.state.isPlaying()) ((now - prevAtMs) * status.speed).toLong() else 0L
                    val jumped = abs((p.positionMs - prevPositionMs) - expectedAdvance) > POSITION_JUMP_MS
                    val durationChanged = meaningfulDurationChange(p.durationMs, prevDurationMs)
                    prevPositionMs = p.positionMs
                    prevAtMs = now
                    prevDurationMs = p.durationMs
                    if (jumped || durationChanged) Unit else null
                },
                // An external metadata source updated → cached snapshots describe() stale items.
                extraInvalidations.onEach { builtFor = null },
            ).collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val status = player.status.value
        val queue = player.queue.value
        val items = queue.items
        val prepared = status.state as? PlaybackState.Prepared
        val error = (status.state as? PlaybackState.Error)?.error

        // media3 has no CONNECTING state: an engine still initializing with a queue reads as
        // BUFFERING, so session surfaces show their loading treatment instead of a dead IDLE.
        val initializing = (status.state as? PlaybackState.Uninitialized)?.isInitializing == true
        val playbackState = when {
            items.isEmpty() || error != null -> Media3Player.STATE_IDLE
            prepared == null -> if (initializing) Media3Player.STATE_BUFFERING else Media3Player.STATE_IDLE
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
            builder.setPlaylist(playlist(items, currentIndex, player.progress.value.durationMs))
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
        applyMediaItems?.invoke(player, mediaItems, startIndex, startPositionMs)
    }

    // COMMAND_CHANGE_MEDIA_ITEMS gates add/remove/move/replace together, and SimpleBasePlayer's
    // defaults THROW for any advertised-but-unimplemented one. Remove/move map onto the facade's
    // range edits. The facade contract is STRICT (in-app index bugs must surface); THIS is the
    // stranger boundary — controller edits arrive async and cross-process against a queue
    // snapshot that may already be stale — so indices clamp here, and only here. Add routes to
    // the owner's converter hook (dropped when no hook is installed). Replace = add+remove.
    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> = done {
        insertMediaItems?.invoke(player, index, mediaItems)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = done {
        val size = player.queue.value.items.size
        val from = fromIndex.coerceIn(0, size)
        val to = toIndex.coerceIn(from, size)
        try {
            if (to > from) player.remove(from, to)
        } catch (e: IllegalArgumentException) {
            // Belt over the clamp: a stale cross-process edit is dropped, never fatal.
            Log.w(TAG, "stale removeMediaItems($fromIndex, $toIndex)", e)
        }
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = done {
        val size = player.queue.value.items.size
        val from = fromIndex.coerceIn(0, size)
        val to = toIndex.coerceIn(from, size)
        val target = newIndex.coerceIn(0, size - (to - from))
        try {
            if (to > from) player.move(from, to, target)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "stale moveMediaItems($fromIndex, $toIndex, $newIndex)", e)
        }
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

    // getState runs only on the main looper (as do the invalidation collectors), so the memo
    // needs no locking. Rebuilding one MediaItemData per queue item on EVERY snapshot is the
    // SimpleBasePlayer tax this avoids: the playlist part changes only when the queue instance,
    // the current index, or the current item's (meaningful) duration does.
    private var builtFor: List<PlayableItem>? = null
    private var builtIndex = -1
    private var builtDurationMs = Long.MIN_VALUE
    private var builtPlaylist: List<MediaItemData> = emptyList()

    private fun playlist(items: List<PlayableItem>, currentIndex: Int, durationMs: Long): List<MediaItemData> {
        if (items !== builtFor || currentIndex != builtIndex || meaningfulDurationChange(durationMs, builtDurationMs)) {
            builtFor = items
            builtIndex = currentIndex
            builtDurationMs = durationMs
            builtPlaylist = items.mapIndexed { i, item ->
                val key = item.mediaKey
                // The engine's measured duration (current item only) beats the item's own
                // source-declared one; other rows fall back to what they carried in.
                val itemDurationMs = when {
                    i == currentIndex && durationMs > 0 -> durationMs
                    else -> item.durationMs?.takeIf { it > 0 }
                }
                MediaItemData.Builder("$i:$key")
                    .setMediaItem(
                        MediaItem.Builder().setMediaId(key).setMediaMetadata(describe(item)).build(),
                    )
                    .setDurationUs(itemDurationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .setIsSeekable((item as? PlayableItem.YtMedia)?.live != true)
                    .build()
            }
        }
        return builtPlaylist
    }

    private inline fun done(block: () -> Unit): ListenableFuture<*> {
        block()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val TAG = "Media3PlayerAdapter"
        const val SEEK_INCREMENT_MS = 10_000L
        const val POSITION_JUMP_MS = 2_000L
        const val DURATION_EPSILON_MS = 1_000L

        /** True when a duration re-anchor is worth a snapshot: it appeared/vanished, or moved by
         *  more than the epsilon — engines refining a known duration by a few ms are noise. */
        fun meaningfulDurationChange(durationMs: Long, prevMs: Long): Boolean =
            (durationMs > 0) != (prevMs > 0) || (durationMs > 0 && abs(durationMs - prevMs) > DURATION_EPSILON_MS)

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
