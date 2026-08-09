package ymusic.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ymusic.player.PlayableItem
import ymusic.player.Player
import ymusic.player.isPlaying
import ymusic.player.media3.Media3PlayerAdapter
import ymusic.ui.AppGraph
import androidx.media3.session.R as Media3R

/**
 * The foreground playback host over the shared app-graph player: a media3 [MediaSessionService]
 * provides the MediaSession surface (media buttons, lock screen, Bluetooth/Auto transport) via
 * [Media3PlayerAdapter], while the notification is built here directly from the `Player` flows —
 * playing keeps the service foreground; pausing demotes it but keeps a dismissible notification
 * (the legacy PlayerService behavior). Started by [YMusicApplication] whenever playback begins;
 * holds
 * wake/wifi locks while playing so the WebView engine keeps streaming with the screen off.
 */
class PlaybackService : MediaSessionService() {
    private var player: Player? = null
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var art: Pair<String, Bitmap?>? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The graph is normally long built when playback starts this service (await returns
        // inline); the cold path (notification action after process death) initializes async —
        // onStartCommand's placeholder covers the foreground contract meanwhile.
        serviceScope.launch {
            init((application as YMusicApplication).graphAsync().await())
        }
    }

    private fun init(graph: AppGraph) {
        val player = graph.player.also { this.player = it }
        val adapter = Media3PlayerAdapter(
            player,
            describe = { item -> item.toMediaMetadata() },
        )
        session = MediaSession.Builder(this, adapter)
            .setSessionActivity(sessionActivityIntent())
            .build()
        serviceScope.launch {
            combine(player.status, player.queue) { status, queue ->
                status.state.isPlaying() to queue.currentItem
            }.distinctUntilChanged().collect { updateNotification() }
        }
        holdLocksWhilePlaying(player)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            player?.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        // super routes MEDIA_BUTTON intents (our notification actions) onto the session.
        val result = super.onStartCommand(intent, flags, startId)
        // The startForegroundService contract: enter foreground now; the collector demotes on pause.
        startForeground(NOTIFICATION_ID, buildNotification())
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        serviceScope.cancel()
        releaseLocks()
        session?.let {
            val adapter = it.player
            it.release()
            adapter.release()
        }
        session = null
        super.onDestroy()
    }

    /** Playing → foreground (undismissable). Paused → demoted, notification stays and is swipeable. */
    private fun updateNotification() {
        val player = this.player ?: return
        if (player.queue.value.currentItem == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            return
        }
        val playing = player.status.value.state.isPlaying()
        val notification = buildNotification()
        if (playing) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val item = player?.queue?.value?.currentItem
        val playing = player?.status?.value?.state?.isPlaying() == true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(item?.displayTitle ?: getString(R.string.app_name))
            .setContentText(item?.displaySubtitle)
            .setContentIntent(sessionActivityIntent())
            .setDeleteIntent(dismissIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Media3R.drawable.media3_icon_previous,
                getString(R.string.notification_previous),
                mediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            )
            .addAction(
                if (playing) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play,
                getString(if (playing) R.string.notification_pause else R.string.notification_play),
                mediaButtonIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )
            .addAction(
                Media3R.drawable.media3_icon_next,
                getString(R.string.notification_next),
                mediaButtonIntent(KeyEvent.KEYCODE_MEDIA_NEXT),
            )
        session?.let { s ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(s.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2),
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
        future.addListener({
            val bitmap = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (art?.first == url) {
                art = url to bitmap
                updateNotification()
            }
        }, ContextCompat.getMainExecutor(this))
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
            player.status.map { it.state.isPlaying() }.distinctUntilChanged().collectLatest { playing ->
                if (playing) {
                    @Suppress("WakelockTimeout")
                    wake.acquire()
                    net.acquire()
                } else {
                    delay(LOCK_RELEASE_GRACE_MS)
                    releaseLocks()
                }
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private companion object {
        const val CHANNEL_ID = "ymusic.playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DISMISS = "ymusic.android.playback.DISMISS"
        const val DISMISS_REQUEST_CODE = 100
        const val LOCK_RELEASE_GRACE_MS = 60_000L
    }
}

/** Session/notification metadata for a queue item — items are self-describing. */
private fun PlayableItem.toMediaMetadata(): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(displaySubtitle)
    displayThumbnailUrl?.let { builder.setArtworkUri(Uri.parse(it)) }
    return builder.build()
}
