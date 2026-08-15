package io.ymusic.desktop.setup

import io.ymusic.player.isLibVlcAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How to get libVLC on this platform, when it is missing.
 *
 * [command] is a package-manager one-liner and [site] the project's own download page. Windows leads
 * with the site because winget is not something most people have used, while the other two platforms
 * lead with the command their users already live in.
 */
data class VlcInstallHint(val command: String?, val site: String, val preferSite: Boolean)

/**
 * Whether libVLC is loadable, and what to tell the user when it is not.
 *
 * Playing a *local* file goes through libVLC (VLCJ), so a missing VLC costs the user their own music
 * library — not YouTube, which plays in the browser engine. That is why the first run mentions it but
 * never blocks on it: it is also the one dependency the app cannot install itself, since VLC ships as
 * a system package or a signed installer, not a binary we can drop in a directory.
 */
object VlcProbe {

    /** Runs libVLC's own discovery — the same search [io.ymusic.player.createLocalPlayer] does, so a
     *  positive answer here means a local file really will play. */
    suspend fun isPresent(): Boolean = withContext(Dispatchers.IO) {
        isLibVlcAvailable()
    }

    fun installHint(): VlcInstallHint {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> VlcInstallHint(
                command = "brew install --cask vlc",
                site = "https://www.videolan.org/vlc/",
                preferSite = false,
            )
            os.contains("win") -> VlcInstallHint(
                command = "winget install VideoLAN.VLC",
                site = "https://www.videolan.org/vlc/",
                preferSite = true,
            )
            else -> VlcInstallHint(
                command = "sudo apt install vlc",
                site = "https://www.videolan.org/vlc/",
                preferSite = false,
            )
        }
    }
}
