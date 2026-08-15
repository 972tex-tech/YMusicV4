package io.ymusic.desktop.update

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the UI shows. A staged update survives a restart, so [Ready] is reached either by
 * downloading now or by finding a complete staged tree from a previous run.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** A newer release exists but nothing has been fetched yet. */
    data class Available(val release: ReleaseInfo, val downloadBytes: Long) : UpdateState

    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState

    /** Staged and verified; applying it is now just a swap plus a relaunch. */
    data class Ready(val release: ReleaseInfo, val staged: File) : UpdateState

    /** The check or download failed. [manualDownloadUrl] is the always-available escape hatch. */
    data class Failed(val reason: String, val manualDownloadUrl: String?) : UpdateState
}

/** The user-facing half of a release — the UI never needs the file list. */
data class ReleaseInfo(val versionCode: Int, val versionName: String, val notes: String, val installerUrl: String)

/**
 * Checks for, downloads, stages and applies updates.
 *
 * Nothing is ever written over the running app: a complete new tree is staged beside it and swapped
 * in one rename ([swapInstall]). Every downloaded file is verified against the hash in the SIGNED
 * manifest before it is used, so the download server is never trusted — only the signing key is.
 *
 * When self-update is impossible (Windows, a read-only install, a dev run) the service still
 * reports availability, and the UI offers the installer download instead: see
 * [InstallLayout.selfUpdateBlockedReason].
 */
class UpdateService(
    private val client: KmpHttpClient,
    private val manifestUrl: String,
    private val publicKey: String,
    private val currentVersionCode: Int,
    private val stagingDir: File,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    /** Download as soon as an update is found, without asking. Mirrors the user's setting. */
    val autoDownload: () -> Boolean = { false },
    private val installRoot: File? = InstallLayout.installRoot(),
    private val platformKey: String = currentPlatformKey(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Null when an in-place update is possible; otherwise why it is not. */
    val selfUpdateBlockedReason: String? = InstallLayout.selfUpdateBlockedReason()

    private val log get() = logger?.fork("update")

    /** Configured at all? Without a pinned key there is nothing to verify against, so checks are
     *  disabled rather than trusting whatever the network returns. */
    val isEnabled: Boolean get() = publicKey.isNotBlank() && manifestUrl.isNotBlank()

    /**
     * Adopt a staged update left by an earlier run. Called at startup BEFORE any check so the app
     * can offer "restart to finish" immediately, and so a completed background download is not
     * fetched twice.
     */
    fun adoptStagedUpdate() {
        val staged = stagedRoots().maxByOrNull { it.second } ?: return
        val (dir, versionCode) = staged
        if (versionCode <= currentVersionCode) {
            dir.deleteRecursively() // already installed, or a downgrade — never keep it around
            return
        }
        _state.value = UpdateState.Ready(
            ReleaseInfo(versionCode, "", "", ""),
            dir,
        )
    }

    private fun stagedRoots(): List<Pair<File, Int>> =
        stagingDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("staged-") }
            .mapNotNull { d -> d.name.removePrefix("staged-").toIntOrNull()?.let { d to it } }

    /** Poll for updates forever; [intervalMs] apart. Cheap — one signed JSON per interval. */
    fun startPeriodicChecks(intervalMs: Long = 6 * 60 * 60 * 1000L) {
        if (!isEnabled) return
        scope.launch {
            while (true) {
                runCatching { check() }
                delay(intervalMs)
            }
        }
    }

    /**
     * Fetch and verify the manifest. Leaves [state] at [UpdateState.Available] (or [Downloading]
     * when the user opted into background downloads), and never downgrades a [Ready] state.
     */
    suspend fun check() {
        if (!isEnabled || _state.value is UpdateState.Ready || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val release = try {
            val body = withContext(Dispatchers.IO) { fetchText(manifestUrl) }
            UpdateManifest.parseVerified(body, publicKey, platformKey)
        } catch (e: UpdateVerificationException) {
            // A manifest that does not verify is hostile, not merely unusable: say so loudly.
            log?.captureError(e, "update manifest failed verification")
            _state.value = UpdateState.Failed("could not verify the update", null)
            return
        } catch (e: Exception) {
            log?.log(Logger.Level.WARN, "update check failed", e)
            _state.value = UpdateState.Idle
            return
        }

        if (release == null || !release.isNewerThan(currentVersionCode)) {
            _state.value = UpdateState.Idle
            return
        }
        val info = ReleaseInfo(release.versionCode, release.versionName, release.notes, release.url)
        val plan = planFor(release)
        _state.value = UpdateState.Available(info, plan.downloadBytes)
        log?.log(Logger.Level.INFO, "update ${release.versionCode} available, ${plan.downloadBytes / 1024}KB to fetch")
        if (autoDownload() && selfUpdateBlockedReason == null) download(release)
    }

    private fun planFor(release: Release): UpdatePlan {
        val root = installRoot ?: return UpdatePlan(release.files, emptyList(), emptyList())
        return planUpdate(hashInstalledFiles(root), release)
    }

    /**
     * Download the missing blobs and stage a complete tree. Reusable files are hardlinked from the
     * current install, so a typical update writes only what changed.
     */
    suspend fun download(release: Release) {
        val root = installRoot ?: run {
            _state.value = UpdateState.Failed("not an installed copy", release.url)
            return
        }
        val info = ReleaseInfo(release.versionCode, release.versionName, release.notes, release.url)
        try {
            withContext(Dispatchers.IO) {
                val plan = planUpdate(hashInstalledFiles(root), release)
                val staged = File(stagingDir, "staged-${release.versionCode}").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val byHash = release.files.groupBy { it.sha256 }
                var done = 0L
                val total = plan.downloadBytes

                // Reusable content first: no network, and it is the bulk of the tree.
                for (entry in plan.reusable) {
                    val source = root.walkTopDown().firstOrNull { it.isFile && sha256Of(it) == entry.sha256 }
                        ?: continue
                    linkOrCopy(source, File(staged, entry.path))
                }
                for (entry in plan.toDownload) {
                    val target = File(staged, entry.path).apply { parentFile?.mkdirs() }
                    fetchTo(release.blobUrl(entry), target) { chunk ->
                        done += chunk
                        _state.value = UpdateState.Downloading(info, done, total)
                    }
                    // The signed manifest says what this content must be; the server is not trusted.
                    val actual = sha256Of(target)
                    if (actual != entry.sha256) error("downloaded ${entry.path} hashed $actual, expected ${entry.sha256}")
                    // The same content may appear at several paths.
                    byHash[entry.sha256].orEmpty().filter { it.path != entry.path }.forEach {
                        linkOrCopy(target, File(staged, it.path))
                    }
                }
                verifyStaged(staged, release)
                _state.value = UpdateState.Ready(info, staged)
            }
            log?.log(Logger.Level.INFO, "update ${release.versionCode} staged and verified")
        } catch (e: Exception) {
            log?.captureError(e, "staging update ${release.versionCode} failed")
            _state.value = UpdateState.Failed(e.message ?: "download failed", release.url)
        }
    }

    /** Every file present with the hash the signed manifest promised — checked before we ever
     *  consider swapping it in. */
    private fun verifyStaged(staged: File, release: Release) {
        for (entry in release.files) {
            val f = File(staged, entry.path)
            check(f.isFile) { "staged tree is missing ${entry.path}" }
            check(sha256Of(f) == entry.sha256) { "staged ${entry.path} does not match the manifest" }
        }
    }

    private fun linkOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) return
        runCatching { java.nio.file.Files.createLink(target.toPath(), source.toPath()) }
            .onFailure { source.copyTo(target, overwrite = true) }
    }

    private suspend fun fetchText(url: String): String {
        val response = client.execute(RequestBuilder().url(url).get().build())
        try {
            check(response.isSuccessful) { "HTTP ${response.code} fetching the update manifest" }
            return response.body.string()
        } finally {
            response.close()
        }
    }

    private suspend fun fetchTo(url: String, target: File, onProgress: (Int) -> Unit) {
        val response = client.execute(RequestBuilder().url(url).get().build())
        try {
            check(response.isSuccessful) { "HTTP ${response.code} fetching $url" }
            val bytes = response.body.byteString().toByteArray()
            target.writeBytes(bytes)
            onProgress(bytes.size)
        } finally {
            response.close()
        }
    }

    /**
     * Swap the staged tree in and relaunch. The caller must exit immediately after — the returned
     * process is the new app.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        val root = installRoot ?: return false
        return runCatching {
            // The staged tree contains the app image contents; swap the whole root.
            swapInstall(root, ready.staged)
            relaunch(root)
            true
        }.onFailure { log?.captureError(it, "applying update failed") }.getOrDefault(false)
    }
}
