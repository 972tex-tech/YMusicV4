package io.ymusic.desktop.update

import io.ymusic.dynamicengine.verifyBundleSignature
import io.ymusic.remoteconfig.AppUpdate
import io.ymusic.remoteconfig.DownloadMismatchException
import io.ymusic.remoteconfig.downloadResumable
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.jetbrains.compose.resources.StringResource
import java.io.File

/** What the UI shows. A verified download survives a restart, so [Ready] can be the first state. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val update: AppUpdate) : UpdateState

    data class Downloading(val update: AppUpdate, val done: Long, val total: Long) : UpdateState

    data class Ready(val update: AppUpdate, val installer: File) : UpdateState

    data class Installing(val update: AppUpdate) : UpdateState

    /** [code] is shown to the user. [trusted] false: [update]'s signature did not verify, so nothing
     *  of it may be downloaded or opened — only its version and notes are shown. */
    data class Failed(val code: String, val update: AppUpdate, val trusted: Boolean = true) : UpdateState
}

/**
 * Turns the config service's `appUpdate` into an installed new version: verify the entry's
 * signature, download the installer, check its hash, hand it to the platform [Installer].
 *
 * The config document is unsigned and cached on disk, so the entry is only trusted through
 * [publicKey]: an installer runs only if its sha256 is the one signed for this platform and version.
 */
class UpdateService internal constructor(
    private val client: KmpHttpClient,
    private val publicKey: String,
    private val currentVersionCode: Int,
    /** `macos-arm64`, …: part of the signed message, so one platform's build cannot pass as another's. */
    private val platform: String,
    /** Where installers are downloaded; nothing else lives here. */
    private val downloadDir: File,
    private val installer: Installer,
    private val logger: Logger? = null,
    /** Download as soon as an update is offered, without asking. Mirrors the user's setting. */
    private val autoDownload: () -> Boolean = { false },
    /** The sha256 of the release whose automatic download failed ("" for none), and its setter. */
    private val autoDownloadFailed: () -> String = { "" },
    private val rememberAutoDownloadFailed: suspend (sha256: String) -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val mutex = Mutex()

    /** An entry that failed verification: reported once, not on every config refresh. */
    private var rejected: AppUpdate? = null

    /** The verified entry the last [offer] accepted — what "Download" fetches. */
    @Volatile var pendingUpdate: AppUpdate? = null
        private set

    val selfUpdateBlockedReason: StringResource? get() = installer.blockedReason

    /** Without a pinned key nothing can be verified, so no update is ever offered. */
    val isEnabled: Boolean get() = publicKey.isNotBlank()

    private val log get() = logger?.fork("update")

    /**
     * Take the config's latest answer. A newer, correctly signed entry becomes [UpdateState.Available]
     * — or [UpdateState.Ready] straight away when its installer is already downloaded.
     */
    suspend fun offer(update: AppUpdate?) {
        if (!isEnabled) return
        mutex.withLock {
            if (update == null || update.versionCode <= currentVersionCode) {
                if (_state.value is UpdateState.Available || _state.value is UpdateState.Failed) _state.value = UpdateState.Idle
                pendingUpdate = null
                return
            }
            if (update == pendingUpdate || update == rejected) return
            val signed = "app-$platform"
            if (!verifyBundleSignature(publicKey, signed, update.versionCode.toLong(), update.sha256, update.signature)) {
                log?.captureError(
                    UpdateVerificationException("$signed ${update.versionCode}: signature does not match the pinned key"),
                )
                pendingUpdate = null
                rejected = update
                _state.value = UpdateState.Failed("E91001", update, trusted = false)
                return
            }
            pendingUpdate = update
            val file = installerFile(update)
            val downloaded = withContext(Dispatchers.IO) { file.isFile && sha256Of(file) == update.sha256 }
            _state.value = if (downloaded && update.installInApp) UpdateState.Ready(update, file) else UpdateState.Available(update)
        }
        // Automatically: once per release. A release that already failed that way waits for a tap.
        if (_state.value is UpdateState.Available && update?.installInApp == true && autoDownload() &&
            selfUpdateBlockedReason == null && update.sha256 != autoDownloadFailed()
        ) {
            download(auto = true)
        }
    }

    /**
     * Fetch [pendingUpdate]'s installer and verify it against the signed hash. Any failure deletes
     * the partial file: the next attempt starts fresh (only a process killed mid-download resumes).
     * An [auto]matic download that fails is remembered and the release offered the normal way; one
     * the user started reports Failed.
     */
    suspend fun download(auto: Boolean = false) {
        val update = mutex.withLock {
            val update = pendingUpdate ?: return
            if (!update.installInApp) return
            if (_state.value.let { it is UpdateState.Downloading || it is UpdateState.Ready || it is UpdateState.Installing }) return
            _state.value = UpdateState.Downloading(update, 0, update.size)
            update
        }
        try {
            val file = withContext(Dispatchers.IO) { fetch(update) }
            _state.value = UpdateState.Ready(update, file)
            log?.log(Logger.Level.INFO, "update ${update.versionCode} downloaded and verified")
        } catch (cancel: CancellationException) {
            _state.value = UpdateState.Available(update)
            throw cancel
        } catch (error: Exception) {
            withContext(Dispatchers.IO) { partFile(update).delete() }
            if (auto) {
                log?.log(Logger.Level.WARN, "automatic download of update ${update.versionCode} failed; offering it instead", error)
                rememberAutoDownloadFailed(update.sha256)
                _state.value = UpdateState.Available(update)
            } else if (error is DownloadMismatchException) {
                log?.captureError(error, "update ${update.versionCode} failed verification")
                _state.value = UpdateState.Failed("E91002", update)
            } else {
                log?.captureError(error, "downloading update ${update.versionCode} failed")
                _state.value = UpdateState.Failed("E91003", update)
            }
        }
    }

    private suspend fun fetch(update: AppUpdate): File {
        downloadDir.mkdirs()
        val target = installerFile(update)
        val part = partFile(update)
        // Another release's leftovers can never be resumed into this one.
        downloadDir.listFiles().orEmpty().filter { it.name.endsWith(".part") && it != part }.forEach { it.deleteRecursively() }
        downloadResumable(client, update.url, part.toOkioPath(), FileSystem.SYSTEM, update.size, update.sha256) { done ->
            _state.value = UpdateState.Downloading(update, done, update.size)
        }
        check(part.renameTo(target)) { "could not move the installer into place" }
        return target
    }

    /**
     * Run the downloaded installer. True: the new version is starting and the caller must exit
     * at once. False: nothing changed (not ready, or the user declined the admin prompt).
     */
    suspend fun install(): Boolean {
        val ready = mutex.withLock {
            val ready = _state.value as? UpdateState.Ready ?: return false
            _state.value = UpdateState.Installing(ready.update)
            ready
        }
        return try {
            installer.install(ready.installer, ready.update).also { started ->
                if (!started) _state.value = ready
            }
        } catch (cancel: CancellationException) {
            _state.value = ready
            throw cancel
        } catch (error: Exception) {
            log?.captureError(error, "installing update ${ready.update.versionCode} failed")
            _state.value = UpdateState.Failed("E91004", ready.update)
            false
        }
    }

    /** Remove installers and partial downloads this version has caught up with; a newer release's
     *  partial download stays for the next attempt to resume. */
    fun pruneDownloads() {
        downloadDir.listFiles().orEmpty().forEach { file ->
            val versionCode = file.name.removePrefix("YMusic-").substringBefore('.').substringBefore('-').toIntOrNull()
            if (versionCode == null || versionCode <= currentVersionCode) file.deleteRecursively()
        }
    }

    /** Named after the version AND hash: a re-published release must not continue the old bytes. */
    private fun partFile(update: AppUpdate) =
        File(downloadDir, "YMusic-${update.versionCode}-${update.sha256.take(16)}.${installer.extension}.part")

    private fun installerFile(update: AppUpdate) =
        File(downloadDir, "YMusic-${update.versionCode}.${installer.extension}")
}

class UpdateVerificationException(message: String) : Exception(message)
