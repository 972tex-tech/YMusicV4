package io.ymusic.desktop.setup

import kmpcommon.io.AsyncSource
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Puts an ffmpeg binary on disk for a fresh install.
 *
 * ffmpeg is what reads titles, artists and cover art out of local files (and what muxes a download
 * into its final container), so the app is half-useful without it — but at ~40 MB per platform it is
 * exactly the kind of payload that should not be inside a download everyone takes. The first run
 * fetches the build for this host instead.
 *
 * The download is only ever trusted through the SIGNED manifest ([ToolsManifest]): the bytes are
 * hashed as they arrive and compared before a single archive entry is read, and the binary is placed
 * atomically so a half-finished fetch can never be found and executed.
 */
class FfmpegProvisioner(
    private val client: KmpHttpClient,
    private val manifestUrl: String,
    private val publicKey: String,
    private val toolsDir: File,
    private val platformKey: String,
) {
    /** Configured at all? Without a URL and a pinned key there is nothing to verify against, so the
     *  app asks the user to install ffmpeg instead of fetching something unauthenticated. */
    val isEnabled: Boolean get() = manifestUrl.isNotBlank() && publicKey.isNotBlank()

    private val binaryName = if (isWindows) "ffmpeg.exe" else "ffmpeg"

    /** The provisioned binary, whatever version, or null when this install has none yet. */
    fun installed(): File? =
        versionDirs().mapNotNull { dir -> File(dir, binaryName).takeIf { it.canExecute() } }.firstOrNull()

    /** Removes every `<version>.part` left by an interrupted run except [keep]. Version directories
     *  themselves are never touched: one only exists after its binary verified and ran. */
    private fun discardStagedAttempts(keep: File?) {
        toolsDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(".part") && it != keep }
            .forEach { it.deleteRecursively() }
    }

    /**
     * Whether a partial download in [staging] belongs to [tool] and can be continued.
     *
     * The marker records what was being fetched, because resuming only makes sense if the bytes
     * already on disk are the head of the SAME file: a republished build at the same url would
     * otherwise be spliced onto the old one's prefix. That would still be caught by the hash — this
     * just avoids wasting a download to discover it.
     */
    private fun resumableFor(staging: File, tool: ToolDownload): Boolean {
        val marker = File(staging, MARKER)
        if (!marker.isFile || !File(staging, "download").isFile) return false
        return marker.readText() == markerFor(tool)
    }

    private fun markerFor(tool: ToolDownload) = "${tool.url}\n${tool.sha256}"

    /** Newest first, so an upgrade wins over the copy it replaced without deleting it mid-run. */
    private fun versionDirs(): List<File> =
        toolsDir.listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.name }

    /**
     * Fetch the manifest and install the build it names, reporting bytes as they land. Returns the
     * binary — already-installed at the published version returns immediately without a download.
     *
     * @throws Exception on any transport, verification or unpack failure; the caller surfaces it as
     * a failed row with a retry, and nothing is left behind for a later run to trip over.
     */
    suspend fun provision(onProgress: (done: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(isEnabled) { "no tools manifest configured" }
        val manifest = fetchText(manifestUrl)
        val tool = ToolsManifest.parseVerified(manifest, publicKey, platformKey)
            ?: error("no ffmpeg build published for $platformKey")

        val target = File(toolsDir, tool.version)
        val binary = File(target, binaryName)
        if (binary.canExecute()) return@withContext binary

        // Closing the window mid-download kills the process, so the catch below never runs and the
        // staging directory outlives it. Anything staged for a DIFFERENT build is swept; a partial
        // archive of the build we still want is kept and resumed.
        val staging = File(toolsDir, "${tool.version}.part")
        discardStagedAttempts(keep = staging.takeIf { resumableFor(it, tool) })
        staging.mkdirs()
        try {
            val archive = File(staging, "download")
            val actual = downloadHashing(tool, archive, onProgress)
            check(actual == tool.sha256) {
                "$binaryName download hashed $actual, the signed manifest says ${tool.sha256}"
            }
            val staged = File(staging, binaryName)
            unpack(archive, tool, staged)
            archive.delete()
            check(staged.isFile && staged.length() > 0) { "archive held no $binaryName" }
            staged.setExecutable(true, /* ownerOnly = */ false)
            adHocSignOnMac(staged)
            // Proven by running it, not by having written it: an unsigned binary on Apple Silicon,
            // a build for the wrong arch and a truncated archive all look fine on disk and only
            // fail at exec. The checklist must not report ready for something that cannot run.
            val version = runVersion(staged)
            check(version != null) { "the downloaded $binaryName does not run on this machine" }

            // Rename last: the version directory appears complete or not at all, so [installed]
            // can never hand back a partly-written binary.
            target.deleteRecursively()
            check(staging.renameTo(target)) { "could not move the staged $binaryName into place" }
            File(target, binaryName)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Streams the build into [target], returning the hex SHA-256 of the whole file.
     *
     * A partial file left by an interrupted run is continued with a range request rather than
     * refetched: the bytes already there are fed through the digest first, so the hash still covers
     * the complete archive and the signed manifest remains the only thing being trusted. A server
     * that ignores the range (answering 200 rather than 206) simply starts the file over.
     */
    private suspend fun downloadHashing(
        tool: ToolDownload,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        File(target.parentFile, MARKER).writeText(markerFor(tool))
        val resumeFrom = target.takeIf { it.isFile }?.length() ?: 0L
        val request = RequestBuilder().url(tool.url)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .get().build()
        val response = client.execute(request)
        try {
            check(response.isSuccessful) { "HTTP ${response.code} fetching ${tool.url}" }
            val resumed = resumeFrom > 0 && response.code == HTTP_PARTIAL_CONTENT
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            if (resumed) {
                target.inputStream().buffered().use { existing ->
                    val chunk = ByteArray(CHUNK.toInt())
                    while (true) {
                        val read = existing.read(chunk)
                        if (read <= 0) break
                        digest.update(chunk, 0, read)
                        done += read
                    }
                }
            }
            val total = response.body.contentLength().takeIf { it > 0 }?.plus(done)
                ?: tool.sizeBytes
            onProgress(done, total)
            val source: AsyncSource = response.body.asyncSource()
            val buffer = Buffer()
            java.io.FileOutputStream(target, /* append = */ resumed).buffered().use { out ->
                while (source.read(buffer, CHUNK) != -1L) {
                    // Drained through readByteArray, which returns exactly what was asked for —
                    // Buffer.read(ByteArray) would stop at the head segment and quietly short-read.
                    while (!buffer.exhausted()) {
                        val chunk = buffer.readByteArray(minOf(buffer.size, CHUNK))
                        digest.update(chunk)
                        out.write(chunk)
                        done += chunk.size
                        onProgress(done, total)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            response.close()
        }
    }

    private fun unpack(archive: File, tool: ToolDownload, target: File) {
        when (tool.format) {
            ArchiveFormat.RAW -> archive.copyTo(target, overwrite = true)
            ArchiveFormat.GZIP -> archive.inputStream().use { raw ->
                GZIPInputStream(raw).use { it.drainTo(target) }
            }
            ArchiveFormat.ZIP -> archive.inputStream().use { raw ->
                ZipInputStream(raw).use { zip ->
                    generateSequence { zip.nextEntry }
                        .firstOrNull { !it.isDirectory && (tool.entry == null || it.name == tool.entry) }
                        ?: error("no ${tool.entry ?: "file"} entry in the ffmpeg archive")
                    zip.drainTo(target)
                }
            }
        }
    }

    private fun InputStream.drainTo(target: File) = target.outputStream().buffered().use { copyTo(it) }

    /**
     * Give the binary an ad-hoc signature on macOS if it arrived without one.
     *
     * Apple Silicon refuses to exec an unsigned Mach-O outright (the kernel kills it), so a build
     * published unsigned would simply never run there. A Developer ID signature applied at publish
     * time is the better answer — this is the floor that keeps the app working regardless of what
     * was published, and it is a no-op for an already-signed binary.
     *
     * Gatekeeper itself is not in play: the file arrives through our own HTTP client, which sets no
     * quarantine attribute, so nothing here needs notarization to launch.
     */
    private fun adHocSignOnMac(binary: File) {
        if (!isMac) return
        if (exec(listOf("codesign", "--verify", binary.absolutePath)) == 0) return
        val rc = exec(listOf("codesign", "--force", "--sign", "-", binary.absolutePath))
        // Not fatal on its own — the exec check that follows is what actually decides.
        if (rc != 0) return
    }

    /** `ffmpeg -version`, or null when it cannot run at all. */
    private fun runVersion(binary: File): String? = runCatching {
        val process = ProcessBuilder(binary.absolutePath, "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        output.takeIf { process.exitValue() == 0 && it.contains("ffmpeg version") }
    }.getOrNull()

    private fun exec(command: List<String>): Int = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start().let { p ->
            p.inputStream.readBytes()
            p.waitFor()
        }
    }.getOrDefault(-1)

    private suspend fun fetchText(url: String): String {
        val response = client.execute(RequestBuilder().url(url).get().build())
        try {
            check(response.isSuccessful) { "HTTP ${response.code} fetching the tools manifest" }
            return response.body.string()
        } finally {
            response.close()
        }
    }

    private companion object {
        const val CHUNK = 64L * 1024
        const val VERSION_TIMEOUT_SECONDS = 20L
        const val HTTP_PARTIAL_CONTENT = 206

        /** Records which build a staging directory holds, so a resume can prove it is the same one. */
        const val MARKER = "source"
    }
}

internal val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

internal val isMac: Boolean get() = System.getProperty("os.name").lowercase().contains("mac")
