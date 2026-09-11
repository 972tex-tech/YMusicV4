package io.ymusic.desktop.update

import io.ymusic.ui.AppUpdateStatus
import io.ymusic.ui.AppUpdates
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
) : AppUpdates {

    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.None)
    override val status: StateFlow<AppUpdateStatus> = _status
    override val blockedReason: String? get() = service.selfUpdateBlockedReason

    private var dismissed: Int = -1

    init {
        scope.launch {
            service.state.collect { s ->
                _status.value = when (s) {
                    is UpdateState.Available ->
                        if (s.release.versionCode == dismissed) AppUpdateStatus.None
                        else AppUpdateStatus.Available(
                            s.release.versionCode, s.release.versionName, s.release.notes, s.downloadBytes,
                        )
                    is UpdateState.Downloading ->
                        AppUpdateStatus.Downloading(s.release.versionName, s.done, s.total)
                    is UpdateState.Ready ->
                        // A ready update is always announced, even if the prompt was dismissed
                        // earlier: it is one click from applying and cost nothing more to offer.
                        AppUpdateStatus.Ready(s.release.versionCode, s.release.versionName, s.release.notes)
                    is UpdateState.Failed -> AppUpdateStatus.Failed(s.reason)
                    UpdateState.Idle, UpdateState.Checking -> AppUpdateStatus.None
                }
            }
        }
    }

    override fun download() {
        val release = service.pendingRelease ?: return
        scope.launch { service.download(release) }
    }

    override fun installAndRestart() {
        // install() relaunches; this process must go, or two copies fight over the data dir.
        if (service.install()) exitProcess(0)
    }

    override fun openDownloadPage() {
        val url = (service.state.value as? UpdateState.Failed)?.manualDownloadUrl
            ?: service.pendingRelease?.url
            ?: return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun dismiss(versionCode: Int) {
        dismissed = versionCode
        if (_status.value is AppUpdateStatus.Available) _status.value = AppUpdateStatus.None
    }
}
