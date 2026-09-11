import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Where the prebuilt ffmpeg binaries come from: assets of the latest github release in
 * ymusic-io/ffmpeg-build.
 *
 * Consumers that exec a binary pack one: the Android apk (per ABI as `libffmpeg.so`) and the
 * desktop app (the host-runnable build, inside the package). Dev `:desktopApp:run` / tests point at
 * the same staged host binary via `-Dio.ymusic.ffmpeg`.
 */
object FfmpegRelease {
    const val REPO = "ymusic-io/ffmpeg-build"

    /** The ABIs the apk and the device tests ship. Fewer than the release carries (no armeabi-v7a). */
    val ANDROID_ABIS = listOf("arm64-v8a", "x86", "x86_64")

    /**
     * A directory of release-named files to use instead of downloading, via `-Pymusic.ffmpegDir=`.
     * Hashes are not checked against it: the point is to try a locally built ffmpeg before it is
     * released, which by definition matches no published hash.
     */
    fun localDir(project: Project): String? = project.providers.gradleProperty("ymusic.ffmpegDir").orNull

    /** The release asset runnable on the machine doing the build, or null where none is published. */
    fun hostAsset(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
        return when {
            os.contains("mac") -> if (arm) "ffmpeg-macos-arm64" else "ffmpeg-macos-x86_64"
            os.contains("linux") -> if (arm) "ffmpeg-linux-arm64" else "ffmpeg-linux-x86_64"
            os.contains("win") -> if (arm) "ffmpeg-windows-arm64.exe" else "ffmpeg-windows-x86_64.exe"
            else -> null
        }
    }

    fun androidAsset(abi: String): String = "ffmpeg-android-$abi.so"

    /** Staged per consumer, so the two sync tasks never write to the same directory. */
    fun hostDir(project: Project): File = File(project.rootProject.layout.buildDirectory.get().asFile, "ffmpeg/host")

    fun androidDir(project: Project): File =
        File(project.rootProject.layout.buildDirectory.get().asFile, "ffmpeg/android")

    /** The host binary's path after a sync, or null when this host has no published build. */
    fun hostBinary(project: Project): File? = hostAsset()?.let { File(hostDir(project), it) }

    fun androidBinary(project: Project, abi: String): File = File(androidDir(project), androidAsset(abi))
}

/**
 * Stages the release assets, fetching only what is not already there.
 *
 * An ordinary build never reaches the network once the binaries are staged: they are an output
 * directory Gradle tracks, so a complete one leaves the task up to date, and deleting one brings it
 * back. Moving to a newer ffmpeg is therefore something you ask for — `-Pymusic.ffmpegUpdate` or
 * `--rerun-tasks` re-reads the latest release and replaces every asset whose hash has changed.
 *
 * Those hashes come from the same release as the binaries, so they catch a truncated or corrupted
 * transfer rather than attesting where the bytes came from — GitHub is the trust boundary here (on
 * macOS the Developer ID signature is a second, independent one). `--offline` skips all of it and
 * uses what is already staged.
 */
abstract class SyncFfmpeg : DefaultTask() {
    @get:Input abstract val repo: Property<String>

    @get:Input abstract val assets: SetProperty<String>

    @get:Input @get:Optional
    abstract val localDir: Property<String>

    @get:Input abstract val offline: Property<Boolean>

    @get:Input abstract val force: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init {
        // Being asked to update means re-checking, and an up-to-date verdict would skip the check.
        outputs.upToDateWhen { !force.get() }
    }

    @TaskAction
    fun sync() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        val wanted = assets.get().sorted()

        localDir.orNull?.let { dir ->
            val local = File(dir)
            for (asset in wanted) {
                val src = File(local, asset)
                require(src.isFile) { "-Pymusic.ffmpegDir=$dir has no $asset" }
                val dest = File(out, asset)
                if (!dest.isFile || sha256(dest) != sha256(src)) {
                    src.copyTo(dest, overwrite = true)
                    logger.lifecycle("ffmpeg: $asset <- $dir (local override, hash not checked)")
                }
                dest.setExecutable(true)
            }
            return
        }

        val missing = wanted.filterNot { File(out, it).isFile }
        // The whole point of the flag: with everything staged and no request to update, a build
        // does not go looking for a newer release.
        if (missing.isEmpty() && !force.get()) return
        if (offline.get()) {
            require(missing.isEmpty()) { "--offline, and ${missing.joinToString()} has never been fetched" }
            return
        }

        // A release that cannot be reached is only fatal when something is actually missing: an
        // already-staged binary was verified when it was fetched, so a flaky network need not stop
        // a build that has everything it needs.
        val release = runCatching { resolve() }.getOrElse { e ->
            require(missing.isEmpty()) { "cannot reach ${repo.get()} releases and ${missing.joinToString()} is missing: $e" }
            logger.lifecycle("ffmpeg: ${repo.get()} unreachable ($e) — keeping the staged binaries")
            return
        }

        for (asset in wanted) {
            val dest = File(out, asset)
            val want = release.sums[asset] ?: error("${release.tag} publishes no $asset")
            if (dest.isFile && sha256(dest) == want) {
                dest.setExecutable(true)
                continue
            }
            logger.lifecycle("ffmpeg: downloading $asset from ${release.tag}")
            // Staged under a temp name so an interrupted download cannot be mistaken for a good file.
            val tmp = File(out, "$asset.part")
            URI("https://github.com/${repo.get()}/releases/download/${release.tag}/$asset").toURL()
                .openStream().use { input -> tmp.outputStream().use(input::copyTo) }
            val got = sha256(tmp)
            if (got != want) {
                tmp.delete()
                error("$asset does not match the SHA256SUMS of ${release.tag}\n  expected $want\n  got      $got")
            }
            tmp.renameTo(dest)
            dest.setExecutable(true)
        }
    }

    private class Release(val tag: String, val sums: Map<String, String>)

    private fun resolve(): Release {
        val tag = latestTag()
        val text = URI("https://github.com/${repo.get()}/releases/download/$tag/SHA256SUMS").toURL().readText()
        // `shasum -a 256 *` format: "<hex>  <name>".
        val sums = text.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*") to parts[0] else null
        }.toMap()
        require(sums.isNotEmpty()) { "$tag publishes an empty or unreadable SHA256SUMS" }
        return Release(tag, sums)
    }

    /** /releases/latest is a redirect to /releases/tag/<tag>; the tag is only in the Location. */
    private fun latestTag(): String {
        val conn = URI("https://github.com/${repo.get()}/releases/latest").toURL()
            .openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            val location = conn.getHeaderField("Location")
                ?: error("${repo.get()} has no releases, or /releases/latest did not redirect")
            location.substringAfterLast("/tag/", "").ifEmpty { error("unexpected redirect: $location") }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
