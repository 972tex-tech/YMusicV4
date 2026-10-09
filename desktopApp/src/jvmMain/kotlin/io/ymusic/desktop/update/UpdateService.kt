package io.ymusic.desktop.update

import io.ymusic.dynamicengine.verifyBundleSignature
import io.ymusic.remoteconfig.AppUpdate
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.File

/** What the UI shows. A verified download survives a restart, so [Ready] can be the first state. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val update: AppUpdate) : UpdateState

    data class Downloading(val update: AppUpdate, val done: Long, val total: Long) : UpdateState

    data class Ready(val update: AppUpdate, val installer: File) : UpdateState

    /** [code] is shown to the user; [manualDownloadUrl] is null when the entry itself is untrusted. */
    data class Failed(val code: String, val manualDownloadUrl: String?) : UpdateState
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
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val mutex = Mutex()

    /** An entry that failed verification: reported once, not on every config refresh. */
    private var rejected: AppUpdate? = null

    /** The verified entry the last [offer] accepted — what "Download" fetches. */
    @Volatile var pendingUpdate: AppUpdate? = null
        private set

    val selfUpdateBlockedReason: String? get() = installer.blockedReason

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
                if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
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
                _state.value = UpdateState.Failed("E91001", null)
                return
            }
            pendingUpdate = update
            val file = installerFile(update)
            val downloaded = withContext(Dispatchers.IO) { file.isFile && sha256Of(file) == update.sha256 }
            _state.value = if (downloaded) UpdateState.Ready(update, file) else UpdateState.Available(update)
        }
        if (_state.value is UpdateState.Available && autoDownload() && selfUpdateBlockedReason == null) download()
    }

    /** Fetch [pendingUpdate]'s installer and verify it against the signed hash. */
    suspend fun download() {
        val update = mutex.withLock {
            val update = pendingUpdate ?: return
            if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
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
        } catch (error: InstallerMismatchException) {
            log?.captureError(error, "update ${update.versionCode} failed verification")
            _state.value = UpdateState.Failed("E91002", update.url)
        } catch (error: Exception) {
            log?.captureError(error, "downloading update ${update.versionCode} failed")
            _state.value = UpdateState.Failed("E91003", update.url)
        }
    }

    private suspend fun fetch(update: AppUpdate): File {
        downloadDir.mkdirs()
        val target = installerFile(update)
        val part = File(downloadDir, target.name + ".part")
        try {
            // no-store: the shared client's disk cache must not keep a copy of the installer.
            val response = client.execute(RequestBuilder().url(update.url).header("Cache-Control", "no-store").get().build())
            val sha256 = try {
                check(response.isSuccessful) { "HTTP ${response.code} fetching ${update.url}" }
                val body = response.body.asyncSource()
                val hashing = HashingSink.sha256(FileSystem.SYSTEM.sink(part.toOkioPath()))
                hashing.buffer().use { out ->
                    val chunk = Buffer()
                    var done = 0L
                    while (true) {
                        val read = body.read(chunk, 64 * 1024L)
                        if (read == -1L) break
                        out.write(chunk, read)
                        done += read
                        if (done > update.size) throw InstallerMismatchException("more than the signed ${update.size} bytes")
                        _state.value = UpdateState.Downloading(update, done, update.size)
                    }
                }
                hashing.hash.hex()
            } finally {
                response.close()
            }
            if (sha256 != update.sha256) throw InstallerMismatchException("hashed $sha256, signed ${update.sha256}")
        } catch (error: Throwable) {
            part.delete()
            throw error
        }
        check(part.renameTo(target)) { "could not move the installer into place" }
        return target
    }

    /**
     * Run the downloaded installer. True: the new version is starting and the caller must exit
     * at once. False: nothing changed (not ready, or the user declined the admin prompt).
     */
    suspend fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        return try {
            installer.install(ready.installer, ready.update)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            log?.captureError(error, "installing update ${ready.update.versionCode} failed")
            _state.value = UpdateState.Failed("E91004", ready.update.url)
            false
        }
    }

    /** Remove installers this version has caught up with, and abandoned partial downloads. */
    fun pruneDownloads() {
        downloadDir.listFiles().orEmpty().forEach { file ->
            val versionCode = file.name.removePrefix("YMusic-").substringBefore('.').toIntOrNull()
            if (versionCode == null || versionCode <= currentVersionCode || file.name.endsWith(".part")) {
                file.deleteRecursively()
            }
        }
    }

    private fun installerFile(update: AppUpdate) =
        File(downloadDir, "YMusic-${update.versionCode}.${installer.extension}")
}

class UpdateVerificationException(message: String) : Exception(message)

private class InstallerMismatchException(message: String) : Exception(message)
