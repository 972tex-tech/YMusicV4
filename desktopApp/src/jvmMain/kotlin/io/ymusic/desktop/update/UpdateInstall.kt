package io.ymusic.desktop.update

import java.io.File
import java.security.MessageDigest

/**
 * Where the running app lives and whether we may replace it in place.
 *
 * Self-update is only offered when the whole install is ours to rewrite. It is refused — and the
 * UI falls back to "download it yourself" — when the app runs from a build tree, when the install
 * directory is not writable (an admin-installed copy under a non-admin user), or on Windows, where
 * a running executable and its loaded DLLs cannot be replaced at all. Windows needs versioned
 * install directories behind a stable launcher; until that exists, pretending otherwise would
 * leave a broken install.
 */
object InstallLayout {

    /** The app image root: `YMusic.app` on macOS, the install directory elsewhere. */
    fun installRoot(): File? {
        // jpackage sets this for its launcher; absent when running from Gradle.
        val appPath = System.getProperty("jpackage.app-path")?.let(::File)
        val root = when {
            appPath != null && appPath.exists() -> macBundleOf(appPath) ?: appPath.parentFile
            else -> return null
        }
        return root.takeIf { it.isDirectory }
    }

    /** `/Applications/YMusic.app/Contents/MacOS/YMusic` → `/Applications/YMusic.app`. */
    private fun macBundleOf(launcher: File): File? =
        generateSequence(launcher) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }

    val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Why an in-place update is impossible, or null when it is. A reason string rather than a
     * boolean: it is shown to the user next to the manual download link.
     */
    fun selfUpdateBlockedReason(): String? {
        if (isWindows) return "Windows cannot replace a running application in place"
        val root = installRoot() ?: return "the app is not running from an installed copy"
        if (!root.canWrite() || !(root.parentFile?.canWrite() ?: false)) {
            return "the install directory is not writable"
        }
        return null
    }
}

/** SHA-256 of a file, streamed — release files include a 44MB JVM image. */
fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Hash every file under [root], keyed by path relative to it — what [planUpdate] compares. */
fun hashInstalledFiles(root: File): Map<String, String> =
    root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to sha256Of(it) }

/**
 * Replace [installRoot] with [staged] and return the launcher to start.
 *
 * The swap is two renames within the same directory, so at no point is there a partially written
 * app: the old copy is moved aside, the new one takes its place, and only then is the old one
 * deleted. A failure between the renames restores the original. Renames are atomic on the same
 * filesystem, which is why staging happens beside the install rather than in a temp dir.
 */
fun swapInstall(installRoot: File, staged: File): File {
    require(staged.isDirectory) { "staged tree missing: $staged" }
    val backup = File(installRoot.parentFile, installRoot.name + ".old-" + System.nanoTime())
    check(installRoot.renameTo(backup)) { "could not move the current install aside" }
    if (!staged.renameTo(installRoot)) {
        backup.renameTo(installRoot) // put it back exactly as it was
        error("could not move the new version into place")
    }
    backup.deleteRecursively()
    return installRoot
}

/** Relaunch the app from [root] and let the caller exit; the new process must not be a child that
 *  dies with us, hence `open -n` on macOS rather than exec'ing the launcher directly. */
fun relaunch(root: File) {
    val cmd = when {
        root.name.endsWith(".app") -> listOf("open", "-n", root.absolutePath)
        else -> listOf(File(root, "bin/YMusic").absolutePath)
    }
    ProcessBuilder(cmd).start()
}
