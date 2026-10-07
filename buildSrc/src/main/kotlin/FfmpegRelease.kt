import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Where the prebuilt ffmpeg **pack** comes from: a sibling `ffmpeg-build/out/<slug>/pack` if one
 * exists, else the latest GitHub release tarball in ymusic-io/ffmpeg-build.
 *
 * A pack is a flat directory: the CLI (`ffmpeg` / `ffmpeg.exe` / Android `libffmpeg.so`) plus the
 * same `libav*` / `libsw*` DSOs MSE loads. Rpath is baked (`@loader_path` / `$ORIGIN`); do not
 * paste `LD_LIBRARY_PATH` when exec-ing the CLI.
 *
 * Consumers that exec the CLI pack the whole directory: the Android apk (per ABI under
 * `lib/<abi>/`) and the desktop app (compose app-resources). Dev `:desktopApp:run` / tests point at
 * the staged host CLI via `-Dio.ymusic.ffmpeg`.
 */
object FfmpegRelease {
    const val REPO = "ymusic-io/ffmpeg-build"

    /** The ABIs the apk and the device tests ship. Fewer than the release carries (no armeabi-v7a). */
    val ANDROID_ABIS = listOf("arm64-v8a", "x86", "x86_64")

    /**
     * A directory of release-named `ffmpeg-*.tar.gz` files to use instead of downloading, via
     * `-Pymusic.ffmpegDir=`. Hashes are not checked: the point is to try a locally packed tarball
     * before it is released.
     */
    fun localDir(project: Project): String? = project.providers.gradleProperty("ymusic.ffmpegDir").orNull

    /** ffmpeg-build out/ slug for this machine (`macos-x86_64`, `linux-arm64`, …), or null. */
    fun hostSlug(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
        return when {
            os.contains("mac") -> if (arm) "macos-arm64" else "macos-x86_64"
            os.contains("linux") -> if (arm) "linux-arm64" else "linux-x86_64"
            os.contains("win") -> if (arm) "windows-arm64" else "windows-x86_64"
            else -> null
        }
    }

    /** Release tarball for this host, or null where none is published. */
    fun hostAsset(): String? = hostSlug()?.let { "ffmpeg-$it.tar.gz" }

    fun androidAsset(abi: String): String = "ffmpeg-android-$abi.tar.gz"

    fun androidSlug(abi: String): String = "android-$abi"

    /** Staged per consumer, so the two sync tasks never write to the same directory. */
    fun hostDir(project: Project): File = File(project.rootProject.layout.buildDirectory.get().asFile, "ffmpeg/host")

    fun androidDir(project: Project): File =
        File(project.rootProject.layout.buildDirectory.get().asFile, "ffmpeg/android")

    fun androidPackDir(project: Project, abi: String): File = File(androidDir(project), abi)

    fun hostCliName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"

    /** The host CLI after a sync, or null when this host has no build. */
    fun hostBinary(project: Project): File? = hostAsset()?.let { File(hostDir(project), hostCliName()) }

    fun androidCli(project: Project, abi: String): File = File(androidPackDir(project, abi), "libffmpeg.so")

    /**
     * Sibling `ffmpeg-build/out/<slug>/pack` if a local build already produced one. Preferred over
     * GitHub so a developer can iterate on ffmpeg-build without publishing a release.
     */
    /** The sibling `ffmpeg-build` packs present for release [assets] (`ffmpeg-<slug>.tar.gz`). */
    fun localBuildPacks(project: Project, assets: Set<String>): List<File> =
        assets.mapNotNull { localBuildPack(project, it.removePrefix("ffmpeg-").removeSuffix(".tar.gz")) }

    fun localBuildPack(project: Project, slug: String): File? {
        val pack = project.rootProject.projectDir.parentFile.resolve("ffmpeg-build/out/$slug/pack")
        if (!pack.isDirectory) return null
        val hasCli = File(pack, "ffmpeg").isFile ||
            File(pack, "ffmpeg.exe").isFile ||
            File(pack, "libffmpeg.so").isFile
        return pack.takeIf { hasCli }
    }

    /** ffmpeg-build prefix (`include/` + `lib/`) MSE CMake links against, if present. */
    fun localBuildPrefix(project: Project, slug: String): File? {
        val prefix = project.rootProject.projectDir.parentFile.resolve("ffmpeg-build/out/$slug")
        return prefix.takeIf { File(it, "include").isDirectory && File(it, "lib").isDirectory }
    }

    fun hostPrefix(project: Project): File? = hostSlug()?.let { localBuildPrefix(project, it) }
}

/**
 * Stages an unpacked pack directory, fetching only what is not already there.
 *
 * Preference: `-Pymusic.ffmpegDir` tarballs, then a sibling `ffmpeg-build/out/<slug>/pack`, then
 * the latest GitHub release `ffmpeg-$name.tar.gz`. An ordinary build never reaches the network
 * once the pack is staged. `-Pymusic.ffmpegUpdate` / `--rerun-tasks` re-reads GitHub when no
 * local pack is present.
 *
 * GitHub hashes catch a truncated transfer rather than attesting provenance — GitHub is the trust
 * boundary (on macOS the Developer ID signature is a second, independent one). `--offline` uses
 * what is already staged.
 */
abstract class SyncFfmpeg : DefaultTask() {
    @get:Input abstract val repo: Property<String>

    @get:Input abstract val assets: SetProperty<String>

    @get:Input @get:Optional
    abstract val localDir: Property<String>

    /** Sibling `ffmpeg-build` packs: a rebuild there re-stages instead of leaving the old binaries. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localPacks: ConfigurableFileCollection

    @get:Input abstract val offline: Property<Boolean>

    @get:Input abstract val force: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init {
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
                val dest = unpackDest(out, asset)
                extractTarGz(src, dest)
                logger.lifecycle("ffmpeg: $asset <- $dir (local override, hash not checked)")
            }
            markCliExecutable(out, wanted)
            return
        }

        val fromLocalBuild = wanted.mapNotNull { asset ->
            val slug = slugOf(asset)
            FfmpegRelease.localBuildPack(project, slug)?.let { asset to it }
        }
        for ((asset, pack) in fromLocalBuild) {
            val dest = unpackDest(out, asset)
            copyPack(pack, dest)
            logger.lifecycle("ffmpeg: $asset <- ${pack.absolutePath}")
        }
        val missing = wanted.filterNot { packLooksPresent(unpackDest(out, it)) }
        if (missing.isEmpty() && !force.get()) return
        if (offline.get()) {
            require(missing.isEmpty()) { "--offline, and ${missing.joinToString()} has never been fetched" }
            return
        }

        val release = runCatching { resolve() }.getOrElse { e ->
            require(missing.isEmpty()) { "cannot reach ${repo.get()} releases and ${missing.joinToString()} is missing: $e" }
            logger.lifecycle("ffmpeg: ${repo.get()} unreachable ($e) — keeping the staged binaries")
            return
        }

        for (asset in missing) {
            val dest = unpackDest(out, asset)
            val want = release.sums[asset] ?: error("${release.tag} publishes no $asset")
            val tarDir = File(out, ".tars").apply { mkdirs() }
            val tar = File(tarDir, asset)
            if (tar.isFile && sha256(tar) == want && packLooksPresent(dest) && !force.get()) {
                continue
            }
            logger.lifecycle("ffmpeg: downloading $asset from ${release.tag}")
            val tmp = File(tarDir, "$asset.part")
            URI("https://github.com/${repo.get()}/releases/download/${release.tag}/$asset").toURL()
                .openStream().use { input -> tmp.outputStream().use(input::copyTo) }
            val got = sha256(tmp)
            if (got != want) {
                tmp.delete()
                error("$asset does not match the SHA256SUMS of ${release.tag}\n  expected $want\n  got      $got")
            }
            tmp.renameTo(tar)
            extractTarGz(tar, dest)
        }
        markCliExecutable(out, wanted)
    }

    private class Release(val tag: String, val sums: Map<String, String>)

    private fun resolve(): Release {
        val tag = latestTag()
        val text = URI("https://github.com/${repo.get()}/releases/download/$tag/SHA256SUMS").toURL().readText()
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

    /** `ffmpeg-android-arm64-v8a.tar.gz` → `android-arm64-v8a`; `ffmpeg-macos-x86_64.tar.gz` → `macos-x86_64`. */
    private fun slugOf(asset: String): String =
        asset.removePrefix("ffmpeg-").removeSuffix(".tar.gz")

    /**
     * Host packs unpack at the output root; Android packs unpack into `<abi>/` so one sync task
     * can stage every ABI.
     */
    private fun unpackDest(out: File, asset: String): File {
        val slug = slugOf(asset)
        return if (slug.startsWith("android-")) File(out, slug.removePrefix("android-")) else out
    }

    private fun packLooksPresent(dir: File): Boolean =
        File(dir, "ffmpeg").isFile || File(dir, "ffmpeg.exe").isFile || File(dir, "libffmpeg.so").isFile

    private fun extractTarGz(tar: File, dest: File) {
        dest.mkdirs()
        dest.listFiles()?.forEach { child ->
            if (child.name == ".tars" || child.name.endsWith(".tar.gz") || child.name.endsWith(".part")) {
                return@forEach
            }
            child.deleteRecursively()
        }
        val p = ProcessBuilder("tar", "-xzf", tar.absolutePath, "-C", dest.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "tar -xzf ${tar.name} failed: $text" }
        check(packLooksPresent(dest)) { "${tar.name} unpacked into $dest but has no ffmpeg CLI" }
    }

    private fun copyPack(src: File, dest: File) {
        if (dest.canonicalFile != src.canonicalFile) {
            dest.mkdirs()
            dest.listFiles()?.forEach { child ->
                if (child.name == ".tars" || child.name.endsWith(".tar.gz") || child.name.endsWith(".part")) {
                    return@forEach
                }
                child.deleteRecursively()
            }
            // Preserve Mach-O symlinks (libavcodec.63.dylib -> libavcodec.63.2.100.dylib).
            // Java copyRecursively dereferences them and would triple the packed size.
            val p = ProcessBuilder("cp", "-a", "${src.absolutePath}/.", dest.absolutePath)
                .redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "cp -a $src -> $dest failed: $text" }
        }
        check(packLooksPresent(dest)) { "$src has no ffmpeg CLI" }
    }

    private fun markCliExecutable(out: File, wanted: List<String>) {
        for (asset in wanted) {
            val dir = unpackDest(out, asset)
            listOf("ffmpeg", "ffmpeg.exe", "libffmpeg.so").forEach { name ->
                File(dir, name).takeIf { it.isFile }?.setExecutable(true)
            }
        }
    }
}
