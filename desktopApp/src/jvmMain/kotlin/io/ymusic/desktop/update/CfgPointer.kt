package io.ymusic.desktop.update

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Switching versions by repointing jpackage's launcher config.
 *
 * jpackage's launcher reads `<appDir>/<Name>.cfg` at startup and resolves `$APPDIR` against that
 * directory — and a classpath entry may point outside it (verified: an app booted with its jars in
 * a sibling directory). So a release lives in its own `app-<versionCode>` directory and switching
 * is one small file, replaced by an atomic rename.
 *
 * Nothing in use is ever written: not the launcher, not the runtime, not the running version's
 * jars. There is therefore no instant at which the app does not exist — the property the
 * rename-the-whole-app approach could not offer.
 *
 * The JVM is the one thing this cannot switch: the launcher loads it from `runtime/` beside itself.
 * A release whose runtime differs must be installed by its installer instead ([runtimeMatches]).
 */
object CfgPointer {

    /** The directory holding the launcher config and the current release's jars. */
    fun appDir(root: File): File =
        File(root, "Contents/app").takeIf { it.isDirectory } ?: File(root, "app")

    /** The `runtime/` the launcher loads the JVM from — untouchable while the app runs. */
    fun runtimeDir(root: File): File =
        File(root, "Contents/runtime").takeIf { it.isDirectory } ?: File(root, "runtime")

    fun cfgFile(root: File): File? =
        appDir(root).listFiles().orEmpty().firstOrNull { it.extension == "cfg" }

    /** Where release [versionCode]'s jars live: `app-<code>` beside the launcher's app dir. */
    fun versionDirFor(root: File, versionCode: Int): File {
        val app = appDir(root)
        return File(app.parentFile, "app-$versionCode")
    }

    /**
     * True when [staged] carries the same runtime as the install, i.e. only the classpath changed
     * and a pointer switch is enough. A JDK bump fails this, and that release needs its installer.
     */
    fun runtimeMatches(root: File, stagedHashes: Map<String, String>, installedHashes: Map<String, String>): Boolean {
        val runtimePrefix = runtimeDir(root).relativeTo(root).path + File.separator
        fun runtimeOnly(m: Map<String, String>) = m.filterKeys { it.startsWith(runtimePrefix) }
        return runtimeOnly(stagedHashes) == runtimeOnly(installedHashes)
    }

    /**
     * Point the launcher at [versionDir].
     *
     * Written to a sibling and moved into place atomically: a torn config would leave an app that
     * cannot start at all, which is worse than not updating. `$APPDIR` still resolves to the
     * launcher's own app dir, so the entries are written relative to it.
     */
    fun repoint(root: File, versionDir: File) {
        val cfg = cfgFile(root) ?: error("no launcher config under ${appDir(root)}")
        val relative = "\$APPDIR/../${versionDir.name}/"
        val rewritten = cfg.readLines().joinToString("\n") { line ->
            when {
                line.startsWith("app.classpath=") ->
                    "app.classpath=" + relative + line.substringAfterLast('/')
                else -> line
            }
        } + "\n"
        val tmp = File(cfg.parentFile, cfg.name + ".new")
        tmp.writeText(rewritten)
        Files.move(tmp.toPath(), cfg.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The version the config currently points at, or null when it still uses the shipped layout. */
    fun currentVersionDirName(root: File): String? =
        cfgFile(root)?.readLines()
            ?.firstOrNull { it.startsWith("app.classpath=") }
            ?.substringAfter("\$APPDIR/../", "")
            ?.substringBefore('/')
            ?.takeIf { it.startsWith("app-") }

    /**
     * Delete release directories no longer pointed at. Startup-only: on Windows the files of a
     * running version are locked, so this is the one moment they are free.
     */
    fun pruneUnusedVersions(root: File): Int {
        val keep = currentVersionDirName(root)
        return appDir(root).parentFile.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("app-\\d+")) && it.name != keep }
            .count { it.deleteRecursively() }
    }
}
