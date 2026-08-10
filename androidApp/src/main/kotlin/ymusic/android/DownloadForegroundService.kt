package ymusic.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import ymusic.download.DownloadChange
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadTask
import ymusic.download.DownloadTasksAggregator

/**
 * Foreground host for active downloads — the dataSync twin of [PlaybackService]'s pattern:
 * [YMusicApplication] (re)starts it whenever work lands in the queue, it renders one summary
 * progress notification off [DownloadTasksAggregator]'s task list, and it exits by itself when
 * the queue drains (posting a dismissible "N finished" summary when anything completed on its
 * watch). The download engine itself lives on the app graph and neither starts nor stops with
 * this service — the service only buys the process foreground standing while work runs.
 *
 * Android 15 caps dataSync foreground time (~6h/day): [onTimeout] demotes instead of crashing —
 * downloads continue while the process lives, and the queue is store-persisted either way.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloads: DownloadService? = null
    private var foreground = false
    private var completed = 0
    private var failed = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            val graph = (application as YMusicApplication).graphAsync().await()
            val downloads = graph.downloads ?: run { stopSelf(); return@launch }
            this@DownloadForegroundService.downloads = downloads
            launch {
                downloads.changes.collect { change ->
                    when {
                        change is DownloadChange.Completed -> completed++
                        change is DownloadChange.StateChanged && change.state == DownloadState.FAILED -> failed++
                    }
                }
            }
            DownloadTasksAggregator({ downloads.snapshot() }, downloads.changes).tasks
                .conflate()
                .collect { tasks ->
                    render(tasks)
                    // Progress events arrive per chunk; conflate + this pace = ≤2 posts/s.
                    delay(UPDATE_THROTTLE_MS)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            // Arrives via the notification action on an already-started service — no foreground
            // contract to honor. The collector sees the states flip and winds the service down.
            downloads?.pauseAll()
            return START_NOT_STICKY
        }
        // The startForegroundService contract: enter foreground now; the collector replaces this
        // placeholder with real progress (or finishes) as soon as the graph is up.
        startForeground(NOTIFICATION_ID, placeholderNotification())
        foreground = true
        return START_NOT_STICKY
    }

    /** Android 15's dataSync time cap: demote, never crash — the queue survives in the store. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun render(tasks: List<DownloadTask>) {
        val working = tasks.filter { it.state == DownloadState.QUEUED || it.state.isActive }
        if (working.isEmpty()) {
            finish()
        } else {
            startForeground(NOTIFICATION_ID, progressNotification(working))
            foreground = true
        }
    }

    /** Queue drained (completed, failed, paused or canceled out): leave a summary if anything
     *  finished on this service's watch, drop foreground, stop. */
    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        if (completed > 0 || failed > 0) {
            NotificationManagerCompat.from(this).notify(DONE_NOTIFICATION_ID, doneNotification())
        }
        stopSelf()
    }

    private fun progressNotification(working: List<DownloadTask>): android.app.Notification {
        val focus = working.firstOrNull { it.state.isActive } ?: working.first()
        val sized = working.filter { it.totalBytes > 0 }
        val done = sized.sumOf { it.downloadedBytes }
        val total = sized.sumOf { it.totalBytes }
        val title =
            if (working.size == 1) focus.title
            else getString(R.string.notification_downloads_multi, working.size)
        val text = when {
            working.size > 1 -> focus.title
            focus.state == DownloadState.PREPARING -> getString(R.string.download_state_preparing)
            focus.state == DownloadState.PROCESSING -> getString(R.string.download_state_processing)
            else -> null
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (total > 0) {
                    setProgress(1000, (done * 1000 / total).toInt(), false)
                } else {
                    setProgress(0, 0, true) // nothing sized yet (still preparing)
                }
            }
            .addAction(0, getString(R.string.downloads_pause_all), pauseAllIntent())
            .build()
    }

    private fun placeholderNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_downloads_running))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)
            .build()

    private fun doneNotification(): android.app.Notification {
        val title =
            if (failed > 0) getString(R.string.notification_downloads_done_failed, completed, failed)
            else getString(R.string.notification_downloads_done, completed)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pauseAllIntent(): PendingIntent = PendingIntent.getService(
        this,
        PAUSE_REQUEST_CODE,
        Intent(this, DownloadForegroundService::class.java).setAction(ACTION_PAUSE_ALL),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "ymusic.downloads"
        const val NOTIFICATION_ID = 2001
        const val DONE_NOTIFICATION_ID = 2002
        const val ACTION_PAUSE_ALL = "ymusic.android.downloads.PAUSE_ALL"
        const val PAUSE_REQUEST_CODE = 200
        const val UPDATE_THROTTLE_MS = 500L
    }
}
