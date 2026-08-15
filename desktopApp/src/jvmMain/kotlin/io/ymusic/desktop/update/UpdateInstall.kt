package io.ymusic.desktop.update

import java.io.File
import java.security.MessageDigest

/**
 * Where the running app lives and how it may be replaced.
 *
 * Self-update is refused — and the UI falls back to "download it yourself" — only when the install
 * is not ours to rewrite: a build-tree run, or a directory we cannot write (an admin-installed
 * copy under a non-admin user).
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
     * Why self-update is impossible, or null when it is possible. A reason string rather than a
     * boolean: it is shown to the user next to the manual download link.
     *
     * An update adds a directory beside the launcher's app dir and rewrites one config file in it,
     * so that directory is the only thing that must be writable — the same on every platform, and
     * true from the first install without the installer laying anything out specially.
     */
    fun selfUpdateBlockedReason(root: File? = installRoot()): String? {
        root ?: return "the app is not running from an installed copy"
        val appDir = CfgPointer.appDir(root)
        if (!appDir.isDirectory || CfgPointer.cfgFile(root) == null) {
            return "this copy has no launcher configuration to update"
        }
        if (!appDir.canWrite() || appDir.parentFile?.canWrite() != true) {
            return "the install directory is not writable"
        }
        return null
    }
}

/**
 * Move [source] onto [target], falling back to copy+delete when a rename cannot cross the boundary.
 *
 * `File.renameTo` returns FALSE between filesystems (verified), and staging does not necessarily
 * share a volume with the install — an app on an external disk, or a home directory on another
 * volume. Without the fallback the update would simply never apply.
 */
internal fun moveDirectory(source: File, target: File): Boolean {
    if (source.renameTo(target)) return true
    // Copy first, delete only once the copy is complete: a failure leaves the source intact.
    if (!source.copyRecursively(target, overwrite = true, onError = { _, _ -> kotlin.io.OnErrorAction.TERMINATE })) {
        target.deleteRecursively()
        return false
    }
    source.deleteRecursively()
    return true
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

/** Relaunch the app from [root] and let the caller exit; the new process must not be a child that
 *  dies with us, hence `open -n` on macOS rather than exec'ing the launcher directly. */
fun relaunchInstalled(root: File) {
    val cmd = when {
        root.name.endsWith(".app") -> listOf("open", "-n", root.absolutePath)
        else -> listOf(File(root, "bin/YMusic").absolutePath)
    }
    ProcessBuilder(cmd).start()
}
