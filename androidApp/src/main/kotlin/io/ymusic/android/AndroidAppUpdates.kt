package io.ymusic.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.DownloadMismatchException
import io.ymusic.remoteconfig.downloadResumable
import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
import io.ymusic.ui.UpdateCheck
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toOkioPath
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * Updates the app from the config service's `appUpdate`: download the APK, check it against the
 * entry's size and sha256, hand it to [PackageInstaller]. The entry needs no signature of ours —
 * Android installs an update only when it is signed with the installed app's certificate and has a
 * higher versionCode.
 */
class AndroidAppUpdates(
    private val context: Context,
    private val client: KmpHttpClient,
    private val currentVersionCode: Int,
    private val scope: CoroutineScope,
    private val logger: Logger?,
    /** Download as soon as an update is offered, without asking. Mirrors the user's setting. */
    private val autoDownload: () -> Boolean,
    /** The sha256 of the release whose automatic download failed ("" for none), and its setter. */
    private val autoDownloadFailed: () -> String,
    private val rememberAutoDownloadFailed: suspend (sha256: String) -> Unit,
    /** Fetch the config now, ignoring its throttle; false when that failed. */
    private val refreshConfig: suspend () -> Boolean,
    /** The config's current update entry. */
    private val latestUpdate: suspend () -> AppUpdate?,
) : AppUpdates {

    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.None)
    override val status: StateFlow<AppUpdateStatus> = _status
    override val blockedReason: String? = null

    private val mutex = Mutex()
    private val downloadDir = File(context.cacheDir, "update")

    @Volatile private var pending: AppUpdate? = null

    @Volatile private var dismissed = -1
    private val log get() = logger?.fork("update")

    /** Take the config's latest answer. */
    suspend fun offer(update: AppUpdate?) {
        mutex.withLock {
            if (update == null || update.versionCode <= currentVersionCode) {
                pending = null
                if (_status.value is AppUpdateStatus.Available) _status.value = AppUpdateStatus.None
                return
            }
            if (update == pending) return
            pending = update
            val apk = apkFile(update)
            val downloaded = withContext(Dispatchers.IO) { apk.isFile && sha256Of(apk) == update.sha256 }
            _status.value = when {
                downloaded && update.installInApp ->
                    AppUpdateStatus.Ready(update.versionCode, update.versionName, update.notes, update.changelogUrl)
                update.versionCode == dismissed -> AppUpdateStatus.None
                else -> AppUpdateStatus.Available(
                    update.versionCode, update.versionName, update.notes, update.size, update.changelogUrl, update.installInApp,
                )
            }
        }
        // Automatically: once per release. A release that already failed that way waits for a tap.
        val status = _status.value
        if (status is AppUpdateStatus.Available && status.installInApp && autoDownload() &&
            pending?.sha256 != autoDownloadFailed()
        ) {
            start(auto = true)
        }
    }

    /** Remove APKs and partial downloads this version has caught up with; a newer release's
     *  partial download stays for the next attempt to resume. */
    fun pruneDownloads() {
        downloadDir.listFiles().orEmpty().forEach { file ->
            val versionCode = file.name.removePrefix("YMusic-").substringBefore('.').substringBefore('-').toIntOrNull()
            if (versionCode == null || versionCode <= currentVersionCode) file.delete()
        }
    }

    override fun download() = start(auto = false)

    /**
     * Fetch [pending]. Any failure deletes the partial file: the next attempt starts fresh (only a
     * process killed mid-download resumes). An automatic download that fails is remembered and the
     * release offered the normal way — no error for something the user never asked for; one the
     * user started reports Failed.
     */
    private fun start(auto: Boolean) {
        scope.launch {
            val update = mutex.withLock {
                val update = pending ?: return@launch
                if (!update.installInApp) return@launch
                if (_status.value is AppUpdateStatus.Downloading || _status.value is AppUpdateStatus.Ready) return@launch
                _status.value = AppUpdateStatus.Downloading(update.versionCode, update.versionName, 0, update.size)
                update
            }
            try {
                withContext(Dispatchers.IO) { fetch(update) }
                _status.value = AppUpdateStatus.Ready(update.versionCode, update.versionName, update.notes, update.changelogUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { partFile(update).delete() }
                if (auto) {
                    log?.log(Logger.Level.WARN, "automatic download of update ${update.versionCode} failed; offering it instead", error)
                    rememberAutoDownloadFailed(update.sha256)
                    _status.value = AppUpdateStatus.Available(
                        update.versionCode, update.versionName, update.notes, update.size, update.changelogUrl, update.installInApp,
                    )
                } else if (error is DownloadMismatchException) {
                    log?.captureError(error, "update ${update.versionCode} failed verification")
                    _status.value = AppUpdateStatus.Failed("E91011")
                } else {
                    log?.captureError(error, "downloading update ${update.versionCode} failed")
                    _status.value = AppUpdateStatus.Failed("E91012")
                }
            }
        }
    }

    private suspend fun fetch(update: AppUpdate) {
        downloadDir.mkdirs()
        val part = partFile(update)
        // Another release's leftovers can never be resumed into this one.
        downloadDir.listFiles().orEmpty().filter { it.name.endsWith(".part") && it != part }.forEach { it.delete() }
        downloadResumable(client, update.url, part.toOkioPath(), FileSystem.SYSTEM, update.size, update.sha256) { done ->
            _status.value = AppUpdateStatus.Downloading(update.versionCode, update.versionName, done, update.size)
        }
        check(part.renameTo(apkFile(update))) { "could not move the APK into place" }
    }

    /** Opens the system install prompt; Android restarts nothing — the user reopens the app. */
    override fun installAndRestart() {
        val update = pending ?: return
        if (_status.value !is AppUpdateStatus.Ready) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(apkFile(update)) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log?.captureError(error, "installing update ${update.versionCode} failed")
                _status.value = AppUpdateStatus.Failed("E91013")
            }
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                // fsync needs the stream open: closing the okio sink would close [out] with it.
                val sink = out.sink().buffer()
                apk.source().use { sink.writeAll(it) }
                sink.flush()
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    override fun openDownloadPage() {
        val url = pending?.let { it.downloadPage ?: it.url } ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun dismiss(versionCode: Int) {
        dismissed = versionCode
        if (_status.value is AppUpdateStatus.Available) _status.value = AppUpdateStatus.None
    }

    override suspend fun checkNow(): UpdateCheck {
        if (!refreshConfig()) return UpdateCheck.Failed
        dismissed = -1
        // A release the user asked about later sits as None with [pending] still set, which offer()
        // would skip as already handled.
        mutex.withLock { if (_status.value is AppUpdateStatus.None) pending = null }
        offer(latestUpdate())
        return if (_status.value is AppUpdateStatus.None) UpdateCheck.UpToDate else UpdateCheck.Found
    }

    /** Named after the version AND hash: a re-published release must not continue the old bytes. */
    private fun partFile(update: AppUpdate) = File(downloadDir, "YMusic-${update.versionCode}-${update.sha256.take(16)}.apk.part")

    private fun apkFile(update: AppUpdate) = File(downloadDir, "YMusic-${update.versionCode}.apk")
}

/** [PackageInstaller]'s answer: shows the system confirmation the session is waiting on. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as? YMusicApplication)?.rootLogger?.fork("update")?.log(
                Logger.Level.WARN,
                "package install ended with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
            )
        }
    }
}

private fun sha256Of(file: File): String {
    val hashing = HashingSink.sha256(blackholeSink())
    hashing.buffer().use { out -> file.source().use { out.writeAll(it) } }
    return hashing.hash.hex()
}
