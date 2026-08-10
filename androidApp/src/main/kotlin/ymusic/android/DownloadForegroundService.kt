package ymusic.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadTask
import ymusic.download.DownloadTasksAggregator

/**
 * Foreground host for active downloads — the dataSync twin of [PlaybackService]'s pattern:
 * [YMusicApplication] (re)starts it whenever work lands in the queue, and it exits by itself when
 * the queue drains. The download engine itself lives on the app graph and neither starts nor
 * stops with this service — the service only buys the process foreground standing while work runs.
 *
 * Notifications are ONE GROUP, never a loose stack: every task gets a child card on a stable
 * per-task id — live progress while queued/running (not dismissible), morphing IN PLACE into a
 * dismissible finished/failed card on the same id — and the group summary is the foreground
 * notification, carrying the aggregate progress and the pause-all action. When the queue drains
 * the summary is re-posted dismissible (so surviving finished cards keep their group container)
 * and the service stops.
 *
 * Android 15 caps dataSync foreground time (~6h/day): [onTimeout] demotes instead of crashing —
 * downloads continue while the process lives, and the queue is store-persisted either way.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloads: DownloadService? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Task ids whose child card currently shows progress (cancel it if the task vanishes). */
    private val showing = HashSet<String>()

    /** Task ids whose terminal card is already posted — a re-render must not re-post it (that
     *  would resurrect a card the user swiped away); a retry removes the id again. */
    private val finalized = HashSet<String>()

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
        startForeground(SUMMARY_NOTIFICATION_ID, placeholderSummary())
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
        releaseLocks()
        super.onDestroy()
    }

    private fun render(tasks: List<DownloadTask>) {
        val nm = NotificationManagerCompat.from(this)
        val present = tasks.mapTo(HashSet()) { it.id }

        // A task that vanished from the queue (removed) loses its progress card; a finalized
        // card is the user's to swipe, not ours to cancel.
        showing.iterator().let { it ->
            while (it.hasNext()) {
                val id = it.next()
                if (id !in present) {
                    nm.cancel(childId(id))
                    it.remove()
                }
            }
        }

        completed = 0
        failed = 0
        for (task in tasks) {
            when {
                task.state == DownloadState.QUEUED || task.state.isActive -> {
                    finalized -= task.id // a retry brings the card back to life
                    showing += task.id
                    nm.notify(childId(task.id), progressChild(task))
                }
                task.state == DownloadState.COMPLETED || task.state == DownloadState.FAILED -> {
                    if (task.state == DownloadState.COMPLETED) completed++ else failed++
                    showing -= task.id
                    if (finalized.add(task.id)) nm.notify(childId(task.id), terminalChild(task))
                }
                else -> { // PAUSED / CANCELED: no card
                    if (showing.remove(task.id)) nm.cancel(childId(task.id))
                    finalized -= task.id
                }
            }
        }

        val working = tasks.filter { it.state == DownloadState.QUEUED || it.state.isActive }
        if (working.isEmpty()) {
            finish(nm)
        } else {
            holdLocks()
            startForeground(SUMMARY_NOTIFICATION_ID, workingSummary(working))
            foreground = true
        }
    }

    /** Downloads are always network work: partial wake + high-perf wifi, held while the queue
     *  works, released the moment it drains — no pause grace like playback, the queue state is
     *  the whole truth here. */
    private fun holdLocks() {
        if (wakeLock == null) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YMusic:downloads")
                .apply { setReferenceCounted(false) }

            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YMusic:downloads")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld != true) {
            @Suppress("WakelockTimeout")
            wakeLock?.acquire()
        }
        if (wifiLock?.isHeld != true) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    /** Queue drained: drop foreground; if finished cards remain, re-post the summary dismissible
     *  so they keep their group container instead of scattering loose into the shade. */
    private fun finish(nm: NotificationManagerCompat) {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        if (completed > 0 || failed > 0) {
            nm.notify(SUMMARY_NOTIFICATION_ID, doneSummary())
        }
        stopSelf()
    }

    // ---- child cards ----

    /** Stable per-task notification id, so progress → terminal morphs in place. */
    private fun childId(taskId: String): Int = CHILD_ID_BASE + (taskId.hashCode() and 0x7FFFFF)

    private fun progressChild(task: DownloadTask): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title)
            .setContentText(
                when (task.state) {
                    DownloadState.QUEUED -> getString(R.string.download_state_queued)
                    DownloadState.PREPARING -> getString(R.string.download_state_preparing)
                    DownloadState.PROCESSING -> getString(R.string.download_state_processing)
                    else -> null
                },
            )
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .apply {
                when {
                    task.state == DownloadState.DOWNLOADING && task.totalBytes > 0 ->
                        setProgress(1000, ((task.progress ?: 0f) * 1000).toInt(), false)
                    task.state == DownloadState.PREPARING || task.state == DownloadState.PROCESSING ->
                        setProgress(0, 0, true)
                    // QUEUED: no bar — a row of idle spinners reads as stuck.
                }
            }
            .build()

    private fun terminalChild(task: DownloadTask): Notification {
        val done = task.state == DownloadState.COMPLETED
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(task.title)
            .setContentText(
                if (done) getString(R.string.notification_download_finished)
                else task.errorMessage ?: getString(R.string.notification_download_failed),
            )
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    // ---- the group summary (doubles as the foreground notification) ----

    private fun workingSummary(working: List<DownloadTask>): Notification {
        val sized = working.filter { it.totalBytes > 0 }
        val done = sized.sumOf { it.downloadedBytes }
        val total = sized.sumOf { it.totalBytes }
        return summaryBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_downloads_multi, working.size))
            .setOngoing(true)
            .apply {
                if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false)
                else setProgress(0, 0, true)
            }
            .addAction(0, getString(R.string.downloads_pause_all), pauseAllIntent())
            .build()
    }

    private fun placeholderSummary(): Notification = summaryBuilder()
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.notification_downloads_running))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    private fun doneSummary(): Notification = summaryBuilder()
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(
            if (failed > 0) getString(R.string.notification_downloads_done_failed, completed, failed)
            else getString(R.string.notification_downloads_done, completed),
        )
        .setAutoCancel(true)
        .build()

    private fun summaryBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())

    /** Lands on the Download manager. The distinct action keeps this PendingIntent from aliasing
     *  the playback notification's plain launch intent (filterEquals compares actions). */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_REQUEST_CODE,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
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
        const val GROUP_KEY = "ymusic.downloads.GROUP"
        const val SUMMARY_NOTIFICATION_ID = 2001
        const val CHILD_ID_BASE = 0x100000
        const val ACTION_PAUSE_ALL = "ymusic.android.downloads.PAUSE_ALL"
        const val PAUSE_REQUEST_CODE = 200
        const val OPEN_REQUEST_CODE = 201
        const val UPDATE_THROTTLE_MS = 500L
    }
}
