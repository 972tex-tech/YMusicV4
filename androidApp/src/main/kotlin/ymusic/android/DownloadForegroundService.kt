package ymusic.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.ui.graphics.asAndroidBitmap
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
import ymusic.imageloader.ImageLoader

/**
 * Foreground host for active downloads — the dataSync twin of [PlaybackService]'s pattern:
 * [YMusicApplication] (re)starts it whenever work lands in the queue, and it exits by itself when
 * the queue drains. The download engine itself lives on the app graph and neither starts nor
 * stops with this service — the service only buys the process foreground standing while work runs.
 *
 * Notification model (one GROUP, never a loose stack):
 *  - Every task owns a card on a stable per-task id: title, small thumbnail (app ImageLoader),
 *    status line, progress. ONGOING only while the task actively works (preparing/downloading/
 *    processing); queued, paused, finished and failed cards are the user's to swipe away.
 *  - A card is posted ONLY on a state transition (recorded in the process-wide [postedStates]
 *    ledger, seeded from the first task snapshot so a fresh service instance never resurrects
 *    cards for historical tasks — especially finished ones the user already dismissed). While a
 *    task is active its card also refreshes in place for progress — it is ongoing then, so there
 *    is no swiped card to resurrect.
 *  - The group summary is the foreground notification while working. On drain it is DETACHED and
 *    morphed in place — canceling a group summary cancels its children (AOSP group semantics), so
 *    it is never removed while any card may still be in the shade.
 *
 * Android 15 caps dataSync foreground time (~6h/day): [onTimeout] demotes instead of crashing —
 * downloads continue while the process lives, and the queue is store-persisted either way.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloads: DownloadService? = null
    private var imageLoader: ImageLoader? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Loaded thumbnails by task id (null = load in flight). Instance-local: after a process
     *  restart the disk-cached image reloads cheaply on the next legitimate post. */
    private val icons = HashMap<String, Bitmap?>()

    private var lastTasks: List<DownloadTask> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            val graph = (application as YMusicApplication).graphAsync().await()
            val downloads = graph.downloads ?: run { stopSelf(); return@launch }
            this@DownloadForegroundService.downloads = downloads
            imageLoader = graph.imageLoader
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
        lastTasks = tasks

        // First sight in this process: record every task's current state WITHOUT posting.
        // Their cards either still sit in the shade (notifications outlive the process) or were
        // deliberately swiped — both must stay exactly as the user left them.
        if (!seeded) {
            seeded = true
            for (task in tasks) postedStates[task.id] = task.state
        }

        // A task gone from the queue (removed) takes its card along; ledger entry too.
        val present = tasks.mapTo(HashSet()) { it.id }
        postedStates.keys.iterator().let { iter ->
            while (iter.hasNext()) {
                val id = iter.next()
                if (id !in present) {
                    nm.cancel(childId(id))
                    icons.remove(id)
                    iter.remove()
                }
            }
        }

        for (task in tasks) {
            if (task.state == DownloadState.CANCELED) {
                if (postedStates.remove(task.id) != null) nm.cancel(childId(task.id))
                icons.remove(task.id)
                continue
            }
            ensureIcon(task)
            val transition = postedStates[task.id] != task.state
            // Post on a transition (a swiped dismissible card only returns when the task
            // genuinely changed state — resume, retry, completion), and refresh in place while
            // active (the card is ongoing then; progress is the only thing changing).
            if (transition || task.state.isActive) {
                nm.notify(childId(task.id), taskCard(task))
                postedStates[task.id] = task.state
            }
        }

        val working = tasks.filter { it.state == DownloadState.QUEUED || it.state.isActive }
        if (working.isEmpty()) {
            finish(nm, tasks)
        } else {
            holdLocks()
            startForeground(SUMMARY_NOTIFICATION_ID, workingSummary(working))
            foreground = true
        }
    }

    /** Queue drained: drop the foreground contract but NEVER cancel the summary while cards may
     *  remain — canceling a group summary cancels its children (AOSP group semantics). Morph it
     *  in place into the idle roll-up instead; remove it only for a truly empty queue. */
    private fun finish(nm: NotificationManagerCompat, tasks: List<DownloadTask>) {
        releaseLocks()
        if (tasks.any { it.state != DownloadState.CANCELED }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            nm.notify(SUMMARY_NOTIFICATION_ID, idleSummary(tasks))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        foreground = false
        stopSelf()
    }

    // ---- per-task cards ----

    /** Stable per-task notification id, so every state morphs the same card in place. */
    private fun childId(taskId: String): Int = CHILD_ID_BASE + (taskId.hashCode() and 0x7FFFFF)

    private fun taskCard(task: DownloadTask): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                when (task.state) {
                    DownloadState.COMPLETED -> android.R.drawable.stat_sys_download_done
                    DownloadState.FAILED -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                },
            )
            .setContentTitle(task.title)
            .setContentText(statusLine(task))
            .setLargeIcon(icons[task.id])
            .setGroup(GROUP_KEY)
            // The user's exact contract: non-dismissible only while the task actively works.
            .setOngoing(task.state.isActive)
            .setAutoCancel(!task.state.isActive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .apply {
                val progress = task.progress
                when {
                    task.state == DownloadState.DOWNLOADING && progress != null ->
                        setProgress(1000, (progress * 1000).toInt(), false)
                    task.state == DownloadState.DOWNLOADING ||
                        task.state == DownloadState.PREPARING ||
                        task.state == DownloadState.PROCESSING ->
                        setProgress(0, 0, true)
                    // A paused card keeps its bar frozen where it stopped.
                    task.state == DownloadState.PAUSED && progress != null ->
                        setProgress(1000, (progress * 1000).toInt(), false)
                    // QUEUED / terminal: no bar.
                }
            }
            .build()

    private fun statusLine(task: DownloadTask): String = when (task.state) {
        DownloadState.QUEUED -> getString(R.string.download_state_queued)
        DownloadState.PREPARING -> getString(R.string.download_state_preparing)
        DownloadState.DOWNLOADING -> getString(R.string.download_state_downloading)
        DownloadState.PROCESSING -> getString(R.string.download_state_processing)
        DownloadState.PAUSED -> getString(R.string.download_state_paused)
        DownloadState.COMPLETED -> getString(R.string.notification_download_finished)
        DownloadState.FAILED -> task.errorMessage ?: getString(R.string.notification_download_failed)
        DownloadState.CANCELED -> "" // never rendered
    }

    /** Kick a one-time thumbnail load; refresh the card in place only while it is ongoing —
     *  a dismissible card must not reappear just because its icon arrived. */
    private fun ensureIcon(task: DownloadTask) {
        val url = task.thumbnailUrl ?: return
        val loader = imageLoader ?: return
        if (icons.containsKey(task.id)) return
        icons[task.id] = null
        serviceScope.launch {
            val bitmap = runCatching {
                loader.load(url, THUMB_PX, THUMB_PX, cacheInMemory = false).asAndroidBitmap()
            }.getOrNull() ?: return@launch
            icons[task.id] = bitmap
            val current = lastTasks.firstOrNull { it.id == task.id } ?: return@launch
            if (current.state.isActive) {
                NotificationManagerCompat.from(this@DownloadForegroundService)
                    .notify(childId(current.id), taskCard(current))
            }
        }
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

    /** The drained-queue roll-up: whatever mix remains, joined ("3 finished · 1 failed · 2 paused").
     *  Dismissing it clears the whole group — the standard "clear finished" affordance. */
    private fun idleSummary(tasks: List<DownloadTask>): Notification {
        val completed = tasks.count { it.state == DownloadState.COMPLETED }
        val failed = tasks.count { it.state == DownloadState.FAILED }
        val paused = tasks.count { it.state == DownloadState.PAUSED }
        val text = listOfNotNull(
            completed.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_finished, it) },
            failed.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_failed, it) },
            paused.takeIf { it > 0 }?.let { getString(R.string.downloads_summary_paused, it) },
        ).joinToString(" · ")
        return summaryBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_notification_channel))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }

    private fun summaryBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())

    // ---- locks / plumbing ----

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
        const val THUMB_PX = 256

        /** Process-wide "last state a card was posted for" ledger — it must outlive service
         *  instances (pause-all drains → service stops → resume starts a fresh one), or every
         *  restart re-posts history. All access is main-thread. */
        val postedStates = HashMap<String, DownloadState>()
        var seeded = false
    }
}
