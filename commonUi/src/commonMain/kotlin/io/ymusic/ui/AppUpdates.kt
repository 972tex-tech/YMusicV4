package io.ymusic.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * The shell's updater, as the shared UI sees it. Desktop and Android implement this; iOS leaves it
 * null (the App Store owns updating) and the UI then shows nothing at all.
 */
interface AppUpdates {
    val status: StateFlow<AppUpdateStatus>

    /** Why an in-place update is impossible here, or null when it is possible. Shown next to the
     *  manual download so a blocked user is told what to do instead of silently getting nothing. */
    val blockedReason: String?

    /** Start fetching now (the "Download" button). No-op once downloading or ready. */
    fun download()

    /** Apply a ready update and relaunch. */
    fun installAndRestart()

    /** Open the release's download page (or its installer) in the browser — the fallback whenever
     *  this release cannot be installed in place, self-update is blocked, or it has failed. */
    fun openDownloadPage()

    /** Dismiss this version's prompt; it must not nag again for the same release. */
    fun dismiss(versionCode: Int)
}

/** What the UI renders. Mirrors the shell's state machine without exposing its internals. */
sealed interface AppUpdateStatus {
    data object None : AppUpdateStatus
    /** [installInApp] false: this release cannot replace the installed copy; offer the download page. */
    data class Available(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val downloadBytes: Long,
        val changelogUrl: String?,
        val installInApp: Boolean,
    ) : AppUpdateStatus
    data class Downloading(val versionName: String, val done: Long, val total: Long) : AppUpdateStatus
    data class Ready(val versionCode: Int, val versionName: String, val notes: String, val changelogUrl: String?) : AppUpdateStatus
    data class Failed(val reason: String) : AppUpdateStatus
}
