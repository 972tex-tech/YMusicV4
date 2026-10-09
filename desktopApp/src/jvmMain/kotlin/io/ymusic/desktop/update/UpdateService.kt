package io.ymusic.desktop.update

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kotlinx.coroutines.CancellationException
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
private const val READY_MARKER = ".ymusic-ready"
private const val READY_SUFFIX = ".ymusic-ready"

class UpdateService(
    private val client: KmpHttpClient,
    private val manifestUrl: String,
    private val publicKey: String,
    private val currentVersionCode: Int,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    /** Download as soon as an update is found, without asking. Mirrors the user's setting. */
    val autoDownload: () -> Boolean = { false },
    private val installRoot: File? = InstallLayout.installRoot(),
    private val platformKey: String = currentPlatformKey(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Null when self-update is possible here; otherwise why it is not. */
    val selfUpdateBlockedReason: String? = InstallLayout.selfUpdateBlockedReason(installRoot)

    private val log get() = logger?.fork("update")

    /** The release the last check found, so a UI "Download" button has something to fetch. */
    @Volatile var pendingRelease: Release? = null
        private set

    /** Configured at all? Without a pinned key there is nothing to verify against, so checks are
     *  disabled rather than trusting whatever the network returns. */
    val isEnabled: Boolean get() = publicKey.isNotBlank() && manifestUrl.isNotBlank()

    /**
     * Adopt a staged update left by an earlier run. Called at startup BEFORE any check so the app
     * can offer "restart to finish" immediately, and so a completed background download is not
     * fetched twice.
     */
    fun adoptStagedUpdate() {
        val root = installRoot ?: return
        val ready = readyVersionDirs(root).maxByOrNull { it.second } ?: return
        val (dir, versionCode) = ready
        if (versionCode <= currentVersionCode) return // already running it; prune deals with the dir
        val lines = readyMarkerFor(dir).readLines()
        _state.value = UpdateState.Ready(
            ReleaseInfo(versionCode, lines.getOrElse(1) { "" }, lines.drop(2).joinToString("\n"), ""),
            dir,
        )
    }

    /** Everything that finished verifying — the marker is written last, so its presence is the
     *  only proof a directory is complete. */
    private fun readyVersionDirs(root: File): List<Pair<File, Int>> {
        val versionDirs = CfgPointer.appDir(root).parentFile.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, READY_MARKER).isFile }
            .mapNotNull { d -> d.name.removePrefix("app-").toIntOrNull()?.let { d to it } }
        val bundles = root.parentFile?.listFiles().orEmpty()
            .filter { it.isDirectory && File(it.parentFile, it.name + READY_SUFFIX).isFile }
            .mapNotNull { d -> d.name.substringAfterLast(".new-").toIntOrNull()?.let { d to it } }
        return versionDirs + bundles
    }

    /** Poll for updates forever; [intervalMs] apart. Cheap — one signed JSON per interval. */
    fun startPeriodicChecks(intervalMs: Long = 6 * 60 * 60 * 1000L) {
        if (!isEnabled) return
        scope.launch {
            while (true) {
                try {
                    check()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log?.log(Logger.Level.WARN, "update check failed", e)
                }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.log(Logger.Level.WARN, "update check failed", e)
            _state.value = UpdateState.Idle
            return
        }

        if (release == null || !release.isNewerThan(currentVersionCode)) {
            _state.value = UpdateState.Idle
            return
        }
        pendingRelease = release
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
     * Fetch release [release] and leave it ready to apply.
     *
     * macOS assembles a COMPLETE bundle beside the install, because a signed .app seals every file
     * it contains — writing inside one breaks its signature. Elsewhere only the launcher's
     * classpath files are fetched into `app-<versionCode>`, and the config pointer switches them.
     *
     * Either way unchanged content is hardlinked from the running copy rather than downloaded, and
     * a directory is named by its version, so two updates never write to the same place.
     */
    suspend fun download(release: Release) {
        val root = installRoot ?: run {
            _state.value = UpdateState.Failed("not an installed copy", release.url)
            return
        }
        val info = ReleaseInfo(release.versionCode, release.versionName, release.notes, release.url)
        try {
            withContext(Dispatchers.IO) {
                val installed = hashInstalledFiles(root)
                val wholeBundle = MacBundleSwap.isSupported && root.name.endsWith(".app")
                if (!wholeBundle &&
                    !CfgPointer.runtimeMatches(root, release.files.associate { it.path to it.sha256 }, installed)
                ) {
                    // Only the classpath can be repointed; the launcher loads the JVM from
                    // runtime/ beside itself, so a JDK bump has to go through the installer.
                    _state.value = UpdateState.Failed("this version needs the full installer", release.url)
                    return@withContext
                }

                val appPrefix = CfgPointer.appDir(root).relativeTo(root).path + File.separator
                val wanted = if (wholeBundle) release.files else release.files.filter { it.path.startsWith(appPrefix) }
                check(wanted.isNotEmpty()) { "release lists no files to install" }

                val target = if (wholeBundle) newBundleDir(root, release.versionCode)
                else CfgPointer.versionDirFor(root, release.versionCode)
                target.deleteRecursively() // an interrupted attempt at this version starts over
                target.mkdirs()

                val onDisk = installed.entries.groupBy({ it.value }, { File(root, it.key) })
                val toFetch = wanted.filter { it.sha256 !in onDisk }
                val total = toFetch.sumOf { it.size }
                var done = 0L
                _state.value = UpdateState.Downloading(info, 0, total)

                for (entry in wanted) {
                    val relative = if (wholeBundle) entry.path else entry.path.removePrefix(appPrefix)
                    val placed = File(target, relative)
                    val reusable = onDisk[entry.sha256]?.firstOrNull { it.isFile }
                    if (reusable != null) {
                        // A hardlink shares content, so an assembled bundle is byte-identical to
                        // the signed one we published.
                        linkOrCopy(reusable, placed)
                    } else {
                        placed.parentFile?.mkdirs()
                        fetchTo(release.blobUrl(entry), placed) {
                            done += it
                            _state.value = UpdateState.Downloading(info, done, total)
                        }
                    }
                    val actual = sha256Of(placed)
                    check(actual == entry.sha256) { "${entry.path} hashed $actual, expected ${entry.sha256}" }
                }
                // Written only once every file verified. It lives BESIDE an assembled bundle, never
                // inside it: an extra file would not be covered by the bundle's signature.
                readyMarkerFor(target).writeText(
                    "${release.versionCode}\n${release.versionName}\n${release.notes}",
                )
                _state.value = UpdateState.Ready(info, target)
            }
            log?.log(Logger.Level.INFO, "update ${release.versionCode} downloaded and verified")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.captureError(e, "downloading update ${release.versionCode} failed")
            _state.value = UpdateState.Failed(e.message ?: "download failed", release.url)
        }
    }

    /** `.YMusic.app.new-<code>` beside the install — same volume, so the exchange can be atomic. */
    private fun newBundleDir(root: File, versionCode: Int) =
        File(root.parentFile, ".${root.name}.new-$versionCode")

    /** Beside an assembled bundle, inside a version directory. */
    private fun readyMarkerFor(target: File): File =
        if (target.name.startsWith(".")) File(target.parentFile, target.name + READY_SUFFIX)
        else File(target, READY_MARKER)

    private fun linkOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) return
        try {
            java.nio.file.Files.createLink(target.toPath(), source.toPath())
        } catch (linkError: Exception) {
            try {
                source.copyTo(target, overwrite = true)
            } catch (copyError: Exception) {
                copyError.addSuppressed(linkError)
                throw copyError
            }
        }
    }

    private suspend fun fetchText(url: String): String {
        client.execute(RequestBuilder().url(url).get().build()).use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} fetching the update manifest" }
            return response.body.string()
        }
    }

    private suspend fun fetchTo(url: String, target: File, onProgress: (Int) -> Unit) {
        client.execute(RequestBuilder().url(url).get().build()).use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} fetching $url" }
            val bytes = response.body.byteString().toByteArray()
            target.writeBytes(bytes)
            onProgress(bytes.size)
        }
    }

    /**
     * Swap the staged tree in and relaunch. The caller must exit immediately after — the returned
     * process is the new app.
     */
    /**
     * Apply the downloaded release and restart.
     *
     * A bundle is exchanged with the install in one atomic operation — no instant where the app is
     * missing, and its signature stays intact because the assembled bundle is what we published.
     * Elsewhere the launcher's config is repointed, which is a single atomic rename of one file.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        val root = installRoot ?: return false
        return runCatching {
            if (ready.staged.name.startsWith(".")) {
                MacBundleSwap.exchange(root, ready.staged)
                ready.staged.deleteRecursively() // now holds the superseded version
                readyMarkerFor(ready.staged).delete()
            } else {
                CfgPointer.repoint(root, ready.staged)
            }
            relaunchInstalled(root)
            true
        }.onFailure { log?.captureError(it, "applying update failed") }.getOrDefault(false)
    }

    /**
     * Delete release directories the running copy has superseded. Startup-only: on Windows the
     * files of a version still running are locked, so this is the one moment they are free.
     */
    fun pruneSupersededVersions() {
        val root = installRoot ?: return
        val removed = runCatching { CfgPointer.pruneUnusedVersions(root) }
            .onFailure { log?.log(Logger.Level.WARN, "pruning superseded versions failed", it) }
            .getOrDefault(0)
        if (removed > 0) log?.log(Logger.Level.INFO, "removed $removed superseded version(s)")
    }
}
