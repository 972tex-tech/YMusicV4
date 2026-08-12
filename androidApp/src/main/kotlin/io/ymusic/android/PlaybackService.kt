package io.ymusic.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.ymusic.player.PlayableItem
import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import io.ymusic.player.media3.Media3PlayerAdapter
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.AppGraph
import io.ymusic.ui.youtubeThumbUrl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.session.R as Media3R

/**
 * The foreground playback host over the shared app-graph player: a media3 [MediaLibraryService]
 * provides the MediaSession surface (media buttons, lock screen, Bluetooth/Auto transport) via
 * [Media3PlayerAdapter] plus the Android Auto browse tree ([MediaBrowseTree]), while the
 * notification is built here directly from the `Player` flows — playing keeps the service
 * foreground; pausing demotes it but keeps a dismissible notification (the legacy PlayerService
 * behavior). Started by [YMusicApplication] whenever playback begins; holds wake/wifi locks while
 * playing so the WebView engine keeps streaming with the screen off.
 *
 * A browse tap arrives as the controller's setMediaItems: [LibraryCallback.onAddMediaItems]
 * re-resolves the bare mediaIds through the tree, and [applyBrowseItems] turns them into facade
 * queues with the phone app's own semantics — a YouTube video plays with watch-next autoplay
 * ([io.ymusic.ui.PlaybackCoordinator.playNow]), a local song queues its whole containing list at its
 * position ([io.ymusic.ui.PlaybackCoordinator.playLocal]).
 */
class PlaybackService : MediaLibraryService() {
    private var player: Player? = null
    private var session: MediaLibrarySession? = null
    private var tree: MediaBrowseTree? = null
    private var coordinator: io.ymusic.ui.PlaybackCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var art: Pair<String, Bitmap?>? = null

    /** Whether WE hold foreground — every promote/demote runs on the main thread through this
     *  service, so the flag is authoritative: promotes are skipped when already foreground, and
     *  a media3-triggered promotion that never leads to playback can be detected and demoted. */
    private var foreground = false
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                (application as YMusicApplication).rootLogger
                    .fork("uncaught", mapOf("scope" to "playback-service")).captureError(e)
            },
    )
    private val searchResults = HashMap<String, List<MediaItem>>() // guarded by itself

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The graph is normally long built when playback starts this service (await returns
        // inline); the cold path (notification action after process death, Auto connecting on a
        // dead process) initializes async — onStartCommand's placeholder covers the foreground
        // contract meanwhile. onGetSession answers null until this completes; Auto retries.
        serviceScope.launch {
            init((application as YMusicApplication).graphAsync().await())
        }
    }

    @OptIn(UnstableApi::class)
    private fun init(graph: AppGraph) {
        val player = graph.player.also { this.player = it }
        val tree = MediaBrowseTree(this, graph).also { this.tree = it }
        coordinator = io.ymusic.ui.PlaybackCoordinator(graph, serviceScope)
        val lockScreenArt = graph.settings.flow(AppSettings.lockScreenAlbumArt)
        val adapter = Media3PlayerAdapter(
            player,
            describe = { item -> item.toMediaMetadata(this, sessionArt = lockScreenArt.value) },
            applyMediaItems = { _, items, startIndex, _ -> applyBrowseItems(items, startIndex) },
            insertMediaItems = { target, index, items -> insertBrowseItems(target, index, items) },
        )
        session = MediaLibrarySession.Builder(this, adapter, LibraryCallback(tree))
            .setSessionActivity(sessionActivityIntent())
            .apply {
                // Artwork through the shared app ImageLoader (https + art://, UI-shared caches)
                // instead of media3's DataSource loader, which knows neither art:// nor our caches.
                // CacheBitmapLoader memoizes the current artwork; our loader stays out of the LRU.
                graph.imageLoader?.let {
                    setBitmapLoader(
                        CacheBitmapLoader(ImageLoaderBitmapLoader(it, serviceScope, artworkAuthority(this@PlaybackService))),
                    )
                }
            }
            .build()
        serviceScope.launch {
            combine(player.status, player.queue) { status, queue ->
                status.state.isPlaying() to queue.currentItem
            }.distinctUntilChanged().collect { updateNotification() }
        }
        holdLocksWhilePlaying(player)
    }

    /** A controller's setMediaItems (Auto browse/search tap), applied with phone-app semantics. */
    private fun applyBrowseItems(items: List<MediaItem>, startIndex: Int) {
        val tree = this.tree ?: return
        val coordinator = this.coordinator ?: return
        val chosen = items.getOrNull(startIndex) ?: items.firstOrNull() ?: return
        val id = chosen.mediaId
        when {
            id.startsWith(MediaBrowseTree.YT_SONG_PREFIX) -> {
                val videoId = id.removePrefix(MediaBrowseTree.YT_SONG_PREFIX)
                // A cache miss (process died since browsing) still plays from the id alone.
                val video = tree.ytItem(videoId) ?: io.ymusic.dynamicengine.yt.YtFeedItem(
                    kind = io.ymusic.dynamicengine.yt.YtFeedItem.KIND_VIDEO,
                    id = videoId,
                    title = chosen.mediaMetadata.title?.toString() ?: videoId,
                    subtitleLine1 = chosen.mediaMetadata.artist?.toString(),
                )
                coordinator.playNow(video)
            }
            id.startsWith(MediaBrowseTree.LOCAL_SONG_PREFIX) -> {
                val (containerId, rowId) = tree.parseLocalSongId(id) ?: return
                serviceScope.launch {
                    val songs = tree.localContext(containerId)
                    val index = songs.indexOfFirst { it.id == rowId }
                    if (index >= 0) coordinator.playLocal(songs, index)
                }
            }
        }
    }

    /** Controller-added items (addMediaItems / legacy addQueueItem) — already resolved by
     *  [LibraryCallback.onAddMediaItems] — converted back and inserted into [player]'s queue.
     *  Local ids need one store lookup for the file uri, hence the launch. */
    private fun insertBrowseItems(player: Player, index: Int, items: List<MediaItem>) {
        val tree = this.tree ?: return
        serviceScope.launch {
            val playable = items.mapNotNull { tree.toPlayableItem(it) }
            if (playable.isNotEmpty()) {
                player.add(playable, at = index.coerceIn(0, player.queue.value.items.size))
            }
        }
    }

    // Headset button multi-click (legacy MediaSessionManager port): media3 natively handles one
    // click (toggle) and two (next) on HEADSETHOOK/PLAY_PAUSE but not three → previous, so those
    // keys are intercepted whole and counted here — 300ms window, 1=toggle 2=next ≥3=prev (the
    // third click resolves immediately).
    private var headsetClickCount = 0
    private var headsetClickJob: Job? = null

    private fun onHeadsetButtonClick() {
        headsetClickJob?.cancel()
        headsetClickCount++
        if (headsetClickCount > 2) {
            resolveHeadsetClicks()
        } else {
            headsetClickJob = serviceScope.launch {
                delay(HEADSET_CLICK_WINDOW_MS)
                resolveHeadsetClicks()
            }
        }
    }

    private fun resolveHeadsetClicks() {
        val clicks = headsetClickCount
        headsetClickCount = 0
        headsetClickJob = null
        val player = this.player ?: return
        when {
            clicks == 1 -> player.togglePlay()
            clicks == 2 -> player.skipNext()
            clicks > 2 -> player.skipPrev()
        }
    }

    /** The Auto browse surface — all list building lives in [MediaBrowseTree]. */
    private inner class LibraryCallback(private val tree: MediaBrowseTree) : MediaLibrarySession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent != null &&
                (keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ) {
                if (keyEvent.action == KeyEvent.ACTION_DOWN) onHeadsetButtonClick()
                return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            future { LibraryResult.ofItem(tree.rootItem(), params) }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(ImmutableList.copyOf(tree.children(parentId, page, pageSize)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            val results = tree.searchItems(query)
            synchronized(searchResults) { searchResults[query] = results }
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = synchronized(searchResults) { searchResults[query] } ?: tree.searchItems(query)
            val sliced = if (pageSize > 0 && page >= 0) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(ImmutableList.copyOf(sliced), params)
        }

        /** Controllers send bare mediaIds; refill each from the tree so the adapter's queue items
         *  carry display metadata (an unknown id passes through — [applyBrowseItems] copes). */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.map { tree.item(it.mediaId) ?: it }.toMutableList()
        }
    }

    /** Callback work on the service scope, bridged to the ListenableFuture media3 expects. */
    private fun <T : Any> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch(Dispatchers.Default) {
            try {
                future.set(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                future.cancel(false)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, Runnable::run)
        return future
    }

    /** media3 posts its own DefaultMediaNotificationProvider notification for an active player
     *  (1.5.1 never did without a bound controller — the gap the hand-built notification grew
     *  in), and its default id is our 1001, which would silently swap styles under us. THIS
     *  OVERRIDE EXISTING (no super call) is what suppresses that. The body handles only what the
     *  (status × queue) collector cannot see: media3 signaling the foreground contract is due (a
     *  media-button service start on a dead process) — promote immediately regardless of play
     *  state. Routine refreshes stay with the collector; mirroring them here would double-build
     *  the same notification. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) {
            if (!foreground) {
                startForeground(NOTIFICATION_ID, buildNotification())
                foreground = true
            }
        } else if (foreground && player?.status?.value?.state?.isPlaying() != true) {
            // The contract-promotion that never became playback (media-button start on a dead
            // process, nothing restored): no (playing × item) transition will ever fire the
            // collector, so demote through the one owner when media3 releases the contract.
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            super.onStartCommand(intent, flags, startId) // LifecycleService dispatches on super
            player?.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            foreground = false
            stopSelf()
            return START_NOT_STICKY
        }
        // super routes MEDIA_BUTTON intents (our notification actions) onto the session.
        val result = super.onStartCommand(intent, flags, startId)
        // The startForegroundService contract: enter foreground now; the collector demotes on pause.
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        // Teardown steps are independent: one failing must not skip the rest (a held wake lock
        // or an unreleased session outliving the service is the real leak).
        runCatching { serviceScope.cancel() }
        releaseLocks()
        session?.let {
            val adapter = it.player
            runCatching { it.release() }
            runCatching { adapter.release() }
        }
        session = null
        art = null
        super.onDestroy()
    }

    /** Playing → foreground (undismissable). Paused → demoted, notification stays and is swipeable. */
    private fun updateNotification() {
        val player = this.player ?: return
        if (player.queue.value.currentItem == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            foreground = false
            return
        }
        val playing = player.status.value.state.isPlaying()
        val notification = buildNotification()
        if (playing) {
            // Repeat startForeground while already foreground is the content refresh, not a
            // state change; the flag only records that the promotion happened.
            startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val item = player?.queue?.value?.currentItem
        val playing = player?.status?.value?.state?.isPlaying() == true
        // Legacy three-line shape: title / artist / album as subText — v4's joined "artist · album"
        // subtitle would print the album twice, so a local item splits it here.
        val contentText: String?
        val subText: String?
        when (item) {
            is PlayableItem.LocalMedia -> {
                contentText = item.artist ?: item.album
                subText = item.album.takeIf { item.artist != null }
            }
            else -> {
                contentText = item?.displaySubtitle
                subText = null
            }
        }
        // Legacy mirrored the outer actions manually in RTL: the glyph keeps its slot, the
        // dispatched command and label swap, so after the system mirrors the row the skip
        // directions still read correctly.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Legacy state-dependent glyph (playing shows the play arrow), not the launcher icon.
            .setSmallIcon(if (playing) Media3R.drawable.media3_icon_play else Media3R.drawable.media3_icon_pause)
            .setContentTitle(item?.displayTitle ?: getString(R.string.app_name))
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(sessionActivityIntent())
            .setDeleteIntent(dismissIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Media3R.drawable.media3_icon_previous,
                getString(if (rtl) R.string.notification_next else R.string.notification_previous),
                mediaButtonIntent(if (rtl) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            )
            .addAction(
                if (playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play,
                getString(if (playing) R.string.notification_pause else R.string.notification_play),
                mediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )
            .addAction(
                Media3R.drawable.media3_icon_next,
                getString(if (rtl) R.string.notification_previous else R.string.notification_next),
                mediaButtonIntent(if (rtl) KeyEvent.KEYCODE_MEDIA_PREVIOUS else KeyEvent.KEYCODE_MEDIA_NEXT),
            )
        session?.let { s ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    // media3 1.7+ exposes only the platform token; MediaStyle still wants compat.
                    .setMediaSession(MediaSessionCompat.Token.fromToken(s.platformToken))
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(dismissIntent()),
            )
        }
        val artUrl = item?.displayThumbnailUrl
        if (artUrl != null) {
            val cached = art
            if (cached?.first == artUrl) {
                cached.second?.let(builder::setLargeIcon)
            } else {
                loadArt(artUrl)
            }
        }
        return builder.build()
    }

    /** One in-flight load keyed by url; on success the notification is rebuilt with the bitmap. */
    private fun loadArt(url: String) {
        val s = session ?: return
        art = url to null
        val future = s.bitmapLoader.loadBitmap(Uri.parse(url))
        future.addListener(
            {
                val bitmap = runCatching { future.get() }.getOrNull() ?: return@addListener
                if (art?.first == url) {
                    art = url to bitmap
                    updateNotification()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun sessionActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dismissIntent(): PendingIntent = PendingIntent.getService(
        this,
        DISMISS_REQUEST_CODE,
        Intent(this, PlaybackService::class.java).setAction(ACTION_DISMISS),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** A notification action as a media-button press — super.onStartCommand routes it to the session. */
    private fun mediaButtonIntent(keyCode: Int): PendingIntent = PendingIntent.getService(
        this,
        keyCode,
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(this, PlaybackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun holdLocksWhilePlaying(player: Player) {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YMusic:playback")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }

        @Suppress("DEPRECATION")
        val net = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YMusic:playback")
            .apply { setReferenceCounted(false) }
            .also { wifiLock = it }
        serviceScope.launch {
            combine(
                player.status.map { it.state.isPlaying() }.distinctUntilChanged(),
                // The wifi lock is only worth its battery cost when something in the queue streams;
                // pure-local playback keeps just the partial wake lock (legacy useWifi gating).
                player.queue.map { q -> q.items.any { it is PlayableItem.YtMedia } }.distinctUntilChanged(),
            ) { playing, online -> playing to online }.distinctUntilChanged().collectLatest { (playing, online) ->
                if (playing) {
                    @Suppress("WakelockTimeout")
                    wake.acquire()
                    if (online) {
                        net.acquire()
                    } else {
                        runCatching { if (net.isHeld) net.release() }
                    }
                } else {
                    delay(LOCK_RELEASE_GRACE_MS)
                    releaseLocks()
                }
            }
        }
    }

    private fun releaseLocks() {
        // release() throws on a racy double-release; a swallowed race beats a crash loop here.
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
    }

    private companion object {
        const val CHANNEL_ID = "io.ymusic.playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISMISS = "io.ymusic.android.playback.DISMISS"
        const val DISMISS_REQUEST_CODE = 100
        const val LOCK_RELEASE_GRACE_MS = 60_000L
        const val HEADSET_CLICK_WINDOW_MS = 300L
    }
}

/** Session/notification metadata for a queue item — items are self-describing. The artworkUri is
 *  published in [artworkContentUri]-wrapped form: Android Auto resolves queue icons only from
 *  local content uris (via [ArtworkProvider]); in-process, [ImageLoaderBitmapLoader] unwraps it.
 *  [sessionArt] false leaves the artwork OUT of the session metadata (nothing on the lock screen /
 *  system media card, per [AppSettings.lockScreenAlbumArt]) — the app's own notification builds
 *  its large icon separately and always carries the art. */
private fun PlayableItem.toMediaMetadata(context: android.content.Context, sessionArt: Boolean): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(displaySubtitle)
    if (sessionArt) {
        // A YouTube item exposes the stable mqdefault url derived from its video id — feed thumb
        // urls carry rotating signature params and expire inside long-lived session uris. Local
        // items already carry the stable art://pic/<id>.
        val target = when (this) {
            is PlayableItem.YtMedia -> youtubeThumbUrl(videoId)
            else -> displayThumbnailUrl
        }
        target?.let { builder.setArtworkUri(artworkContentUri(context, it)) }
    }
    return builder.build()
}
