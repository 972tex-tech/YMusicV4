package io.ymusic.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.DownloadMismatchException
import io.ymusic.remoteconfig.downloadResumable
import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
import io.ymusic.ui.UpdateCheck
import io.ymusic.ui.UpdateDownload
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
import org.jetbrains.compose.resources.StringResource
import java.io.File

/**
 * Updates the app from the config service's `appUpdate`: download the APK, check it against the
 * entry's size and sha256, hand it to [PackageInstaller]. The entry needs no signature of ours —
 * the session is pinned to our package, and Android installs an update only when it is signed with
 * the installed app's certificate and has a higher versionCode.
 *
 * A download or install the user asks for first needs the OS's install permission ("Install unknown
 * apps"); without it the user either grants it ([allowInstall], continued in [onResume]) or takes
 * the release from the browser.
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
    /** The config's official download site, if it names one. */
    private val downloadSite: () -> String?,
) : AppUpdates {

    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.None)
    override val status: StateFlow<AppUpdateStatus> = _status
    override val blockedReason: StringResource? = null

    private val mutex = Mutex()
    private val downloadDir = File(context.cacheDir, "update")

    @Volatile private var pending: AppUpdate? = null

    /** What the user asked for before being sent to grant the install permission: install, or download. */
    @Volatile private var installAfterPermission = false

    private val log get() = logger?.fork("update")

    private val currentDownload get() = (_status.value as? AppUpdateStatus.Offered)?.download

    private fun offered(update: AppUpdate, download: UpdateDownload) = AppUpdateStatus.Offered(
        versionCode = update.versionCode,
        versionName = update.versionName,
        notes = update.notes,
        changelogUrl = update.changelogUrl,
        installInApp = update.installInApp,
        downloadPage = update.downloadPage ?: downloadSite() ?: update.url,
        download = download,
    )

    /** Take the config's latest answer. */
    suspend fun offer(update: AppUpdate?) {
        mutex.withLock {
            if (update == null || update.versionCode <= currentVersionCode) {
                pending = null
                if (currentDownload.let { it is UpdateDownload.Idle || it is UpdateDownload.Failed || it is UpdateDownload.NeedsInstallPermission }) {
                    _status.value = AppUpdateStatus.None
                }
                return
            }
            if (update == pending) return
            pending = update
            val apk = apkFile(update)
            val downloaded = withContext(Dispatchers.IO) { apk.isFile && sha256Of(apk) == update.sha256 }
            _status.value = offered(update, if (downloaded && update.installInApp) UpdateDownload.Ready else UpdateDownload.Idle)
        }
        // Automatically: once per release. A release that already failed that way waits for a tap.
        if (currentDownload is UpdateDownload.Idle && pending?.installInApp == true && autoDownload() &&
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

    override fun download() {
        if (canInstall()) start(auto = false) else needInstallPermission(install = false)
    }

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
                if (currentDownload is UpdateDownload.Running || currentDownload is UpdateDownload.Ready) return@launch
                _status.value = offered(update, UpdateDownload.Running(0, update.size))
                update
            }
            try {
                withContext(Dispatchers.IO) { fetch(update) }
                _status.value = offered(update, UpdateDownload.Ready)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { partFile(update).delete() }
                if (auto) {
                    log?.log(Logger.Level.WARN, "automatic download of update ${update.versionCode} failed; offering it instead", error)
                    rememberAutoDownloadFailed(update.sha256)
                    _status.value = offered(update, UpdateDownload.Idle)
                } else if (error is DownloadMismatchException) {
                    log?.captureError(error, "update ${update.versionCode} failed verification")
                    _status.value = offered(update, UpdateDownload.Failed("E91011"))
                } else {
                    log?.captureError(error, "downloading update ${update.versionCode} failed")
                    _status.value = offered(update, UpdateDownload.Failed("E91012"))
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
            _status.value = offered(update, UpdateDownload.Running(done, update.size))
        }
        check(part.renameTo(apkFile(update))) { "could not move the APK into place" }
    }

    /** Opens the system install prompt; Android restarts nothing — the user reopens the app. */
    override fun installAndRestart() {
        val update = pending ?: return
        if (currentDownload !is UpdateDownload.Ready) return
        if (!canInstall()) return needInstallPermission(install = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(apkFile(update)) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log?.captureError(error, "installing update ${update.versionCode} failed")
                _status.value = offered(update, UpdateDownload.Failed("E91013"))
            }
        }
    }

    private fun needInstallPermission(install: Boolean) {
        val update = pending ?: return
        installAfterPermission = install
        _status.value = offered(update, UpdateDownload.NeedsInstallPermission)
    }

    override fun allowInstall() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Back in the app: a request held for the install permission continues once it is granted. */
    fun onResume() {
        val update = pending ?: return
        if (currentDownload !is UpdateDownload.NeedsInstallPermission || !canInstall()) return
        if (installAfterPermission) {
            _status.value = offered(update, UpdateDownload.Ready)
            installAndRestart()
        } else {
            _status.value = offered(update, UpdateDownload.Idle)
            start(auto = false)
        }
    }

    private fun canInstall(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        // API 24-25 have no per-app grant, only the global "Unknown sources" switch.
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Without it a session installs whatever package the APK holds, as a new app.
        params.setAppPackageName(context.packageName)
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

    override suspend fun checkNow(): UpdateCheck {
        if (!refreshConfig()) return UpdateCheck.Failed
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
