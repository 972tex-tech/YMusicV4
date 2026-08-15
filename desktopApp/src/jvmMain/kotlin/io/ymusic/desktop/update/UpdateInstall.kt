package io.ymusic.desktop.update

import java.io.File
import java.security.MessageDigest

/**
 * How a platform's install is replaced.
 *
 * The difference is not cosmetic: Unix lets a running process's files be renamed out from under
 * it, Windows holds a mandatory lock on the running .exe and every loaded DLL. So each gets the
 * only scheme that is safe there.
 */
enum class SwapStrategy {
    /** macOS/Linux: rename the whole app root aside and the new tree into its place. */
    REPLACE_IN_PLACE,

    /**
     * Windows: never touch the running copy. Each release lives in its own `app-<versionCode>`
     * directory under the install root, and a stable launcher runs the newest complete one — so
     * "applying" an update is writing a new directory and restarting, and the superseded directory
     * is deleted at the next start, when nothing holds it open.
     */
    VERSIONED_DIRECTORIES,
}

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
     * Decided by what is on disk, not by the OS: an install laid out as `app-<n>` directories is
     * updated by adding another one, anything else by replacing the root. Windows REQUIRES the
     * versioned layout (its running .exe and loaded DLLs cannot be replaced), so a Windows install
     * that lacks it is refused rather than corrupted — see [selfUpdateBlockedReason].
     */
    fun strategyFor(root: File?): SwapStrategy =
        if (root != null && hasVersionDirs(root)) SwapStrategy.VERSIONED_DIRECTORIES
        else SwapStrategy.REPLACE_IN_PLACE

    private fun hasVersionDirs(root: File): Boolean =
        root.listFiles().orEmpty().any { it.isDirectory && it.name.matches(Regex("app-\\d+")) }

    /**
     * Why self-update is impossible, or null when it is possible. A reason string rather than a
     * boolean: it is shown to the user next to the manual download link.
     */
    fun selfUpdateBlockedReason(root: File? = installRoot()): String? {
        root ?: return "the app is not running from an installed copy"
        val strategy = strategyFor(root)
        if (isWindows && strategy != SwapStrategy.VERSIONED_DIRECTORIES) {
            // The installer has not laid the app out in per-version directories, and replacing a
            // running Windows install in place is not possible: its .exe and DLLs are locked.
            return "this installer does not support in-place updates on Windows"
        }
        // In-place renames the root, so its PARENT must be writable; versioned only adds a sibling
        // inside the root.
        val needed = if (strategy == SwapStrategy.REPLACE_IN_PLACE) root.parentFile else root
        if (needed?.canWrite() != true) return "the install directory is not writable"
        return null
    }
}

/** `<root>/app-<versionCode>` — one directory per release under a Windows install. */
fun versionDir(root: File, versionCode: Int): File = File(root, "app-$versionCode")

/**
 * Install [staged] as [versionCode] beside the running copy and return the launcher to start.
 *
 * Nothing in use is written to, so this is safe while the app runs — the point of the layout. The
 * directory is moved into place only after it is complete, so a launcher that picks the newest can
 * never select a half-written release.
 */
fun installVersioned(root: File, staged: File, versionCode: Int): File {
    require(staged.isDirectory) { "staged tree missing: $staged" }
    val target = versionDir(root, versionCode)
    target.deleteRecursively()
    check(staged.renameTo(target)) { "could not move the new version into $target" }
    return File(root, if (InstallLayout.isWindows) "YMusic.exe" else "YMusic")
}

/**
 * Delete superseded release directories, keeping [keepVersionCode] (the running one).
 *
 * Called at STARTUP: on Windows the previous version's files are locked while it runs, so cleanup
 * has to wait until the process that held them is gone.
 */
fun pruneOldVersions(root: File, keepVersionCode: Int): Int =
    root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("app-") }
        .mapNotNull { d -> d.name.removePrefix("app-").toIntOrNull()?.let { d to it } }
        .filter { (_, code) -> code != keepVersionCode }
        .count { (dir, _) -> dir.deleteRecursively() }

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
