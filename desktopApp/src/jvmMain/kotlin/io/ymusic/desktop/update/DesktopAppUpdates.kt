package io.ymusic.desktop.update

import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
import io.ymusic.ui.UpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import kotlin.system.exitProcess

/**
 * Binds [UpdateService] to the shared UI's [AppUpdates].
 *
 * Dismissals live here rather than in the service: they are a UI concern (this user said "later"
 * to this version), and they must not stop a background download the user already opted into.
 */
class DesktopAppUpdates(
    private val service: UpdateService,
    private val scope: CoroutineScope,
    /** Fetch the config now, ignoring its throttle; false when that failed. */
    private val refreshConfig: suspend () -> Boolean,
    /** The config's current update entry. */
    private val latestUpdate: suspend () -> AppUpdate?,
) : AppUpdates {

    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.None)
    override val status: StateFlow<AppUpdateStatus> = _status
    override val blockedReason: String? get() = service.selfUpdateBlockedReason

    init {
        scope.launch {
            service.state.collect { s -> _status.value = statusOf(s) }
        }
    }

    private fun statusOf(s: UpdateState): AppUpdateStatus = when (s) {
        is UpdateState.Available ->
            AppUpdateStatus.Available(
                s.update.versionCode, s.update.versionName, s.update.notes, s.update.size,
                s.update.changelogUrl, s.update.installInApp,
            )
        is UpdateState.Downloading ->
            AppUpdateStatus.Downloading(s.update.versionCode, s.update.versionName, s.done, s.total)
        is UpdateState.Ready ->
            AppUpdateStatus.Ready(s.update.versionCode, s.update.versionName, s.update.notes, s.update.changelogUrl)
        is UpdateState.Failed -> AppUpdateStatus.Failed(s.code)
        UpdateState.Idle -> AppUpdateStatus.None
    }

    override suspend fun checkNow(): UpdateCheck {
        if (!refreshConfig()) return UpdateCheck.Failed
        service.offer(latestUpdate())
        _status.value = statusOf(service.state.value)
        return if (_status.value is AppUpdateStatus.None) UpdateCheck.UpToDate else UpdateCheck.Found
    }

    override fun download() {
        scope.launch { service.download() }
    }

    override fun installAndRestart() {
        // The new version is already starting; this process must go, or two copies fight over
        // the data dir — and on Windows the installer waits for it.
        scope.launch { if (service.install()) exitProcess(0) }
    }

    override fun openDownloadPage() {
        val url = (service.state.value as? UpdateState.Failed)?.let { it.manualDownloadUrl ?: return }
            ?: service.pendingUpdate?.let { it.downloadPage ?: it.url }
            ?: return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }
}
