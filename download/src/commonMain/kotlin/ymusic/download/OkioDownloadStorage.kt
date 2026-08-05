package ymusic.download

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * [DownloadStorage] over an okio [FileSystem] — desktop (`~/.ymusic`) and iOS (NSDocument). Scratch files
 * live under [scratchDir]; [publish] moves the finished file into the chosen `file://` folder (or
 * [outputDir] when none). A non-`file:` destination (Android SAF `content://`) is handed to
 * [contentPublisher], which the shell provides; without one, such a destination is an error.
 */
class OkioDownloadStorage(
    private val fileSystem: FileSystem,
    private val scratchDir: Path,
    private val outputDir: Path,
    private val contentPublisher: ContentPublisher? = null,
) : DownloadStorage {

    init {
        fileSystem.createDirectories(scratchDir)
        fileSystem.createDirectories(outputDir)
    }

    override fun scratch(name: String): DownloadScratch = OkioDownloadScratch(fileSystem, scratchDir / name)

    override fun deleteScratch(taskId: String) {
        fileSystem.deleteRecursively(scratchDir / taskId, mustExist = false)
    }

    override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String?): String {
        // A content:// (or any non-file) folder can't be an okio path — delegate to the platform publisher.
        if (destinationFolder != null && !destinationFolder.startsWith("file:")) {
            val publisher = contentPublisher
                ?: error("no ContentPublisher for destination $destinationFolder")
            val uri = publisher.publish(scratch.path, destinationFolder, fileName)
            scratch.delete()
            return uri
        }
        val dir = destinationFolder?.let { fileUriToPath(it) } ?: outputDir
        fileSystem.createDirectories(dir)
        val dest = dir / fileName
        val src = scratch.path.toPath()
        fileSystem.delete(dest, mustExist = false)
        // Rename within the same filesystem; fall back to copy+delete across mounts.
        runCatching { fileSystem.atomicMove(src, dest) }.onFailure {
            fileSystem.source(src).use { s -> fileSystem.sink(dest).buffer().use { it.writeAll(s) } }
            fileSystem.delete(src, mustExist = false)
        }
        return dest.toString()
    }

    /** `file:///abs/path` → an okio [Path] (strips the scheme; leaves the leading slash). */
    private fun fileUriToPath(fileUri: String): Path =
        fileUri.removePrefix("file://").removePrefix("localhost").ifEmpty { "/" }.toPath()
}

/** okio-backed [DownloadScratch]: append-only writes (resume via on-disk size) + a real path for ffmpeg. */
private class OkioDownloadScratch(
    private val fileSystem: FileSystem,
    private val file: Path,
) : DownloadScratch {

    init {
        file.parent?.let { fileSystem.createDirectories(it) }
    }

    override val path: String get() = file.toString()

    override fun length(): Long =
        if (fileSystem.exists(file)) fileSystem.metadata(file).size ?: 0L else 0L

    override fun appendSink(): BufferedSink = fileSystem.appendingSink(file).buffer()

    override fun delete() = fileSystem.delete(file, mustExist = false)

    override fun close() {}
}
