package io.ymusic.desktop.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker
import java.io.File

/**
 * Downloads and unpacks the Chromium bundle JCEF needs, without initializing it.
 *
 * Everything the app does with YouTube runs inside this browser, so there is no useful app before it
 * is on disk — which is why the first run waits for it rather than deferring it. The bundle is ~150 MB
 * and jcefmaven owns its own integrity checking, mirrors and unpack; this only drives its progress
 * into the UI and keeps the install directory in step with [io.ymusic.desktop.CefManager].
 *
 * Deliberately install-only ([CefAppBuilder.install], not `build`): initializing CefApp is
 * once-per-process and belongs to the real boot, which then finds the bundle already there.
 */
class CefProvisioner(private val installDir: File) {

    /** True when a complete, checksum-matched bundle is already unpacked here. */
    fun isInstalled(): Boolean =
        runCatching { CefInstallationChecker.checkInstallation(installDir) }.getOrDefault(false)

    /**
     * Fetch and unpack the bundle, reporting 0..1 progress. Extraction and install have no byte
     * counts of their own, so they continue the same bar over the tail of the range rather than
     * restarting it — a bar that resets reads as a stall.
     */
    suspend fun install(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        CefAppBuilder().apply {
            setInstallDir(installDir)
            setProgressHandler(
                IProgressHandler { state, percent ->
                    val fraction = (percent / 100f).coerceIn(0f, 1f)
                    onProgress(
                        when (state) {
                            EnumProgress.LOCATING -> 0f
                            EnumProgress.DOWNLOADING -> fraction * DOWNLOAD_SHARE
                            EnumProgress.EXTRACTING -> DOWNLOAD_SHARE + fraction * EXTRACT_SHARE
                            else -> 1f
                        },
                    )
                },
            )
        }.install()
        onProgress(1f)
    }

    private companion object {
        /** The download dominates the wait; the rest is unpacking a local archive. */
        const val DOWNLOAD_SHARE = 0.85f
        const val EXTRACT_SHARE = 0.15f
    }
}
