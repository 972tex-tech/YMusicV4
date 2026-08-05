package ymusic.download

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * [DownloadStorage] over an okio [FileSystem] — desktop (`~/.ymusic`) and iOS (NSDocument). Scratch files
 * live under [scratchDir]; [publish] renames the finished file into [outputDir] and returns its path.
 * Android uses a SAF-backed implementation instead (Library folder → `content://`, TODO).
 */
class OkioDownloadStorage(
    private val fileSystem: FileSystem,
    private val scratchDir: Path,
    private val outputDir: Path,
) : DownloadStorage {

    init {
        fileSystem.createDirectories(scratchDir)
        fileSystem.createDirectories(outputDir)
    }

    override fun scratch(name: String): DownloadScratch = OkioDownloadScratch(fileSystem, scratchDir / name)

    override fun deleteScratch(taskId: String) {
        fileSystem.deleteRecursively(scratchDir / taskId, mustExist = false)
    }

    override fun writeScratchBlob(name: String, bytes: ByteArray) {
        val path = scratchDir / name
        path.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(path) { write(bytes) }
    }

    override fun readScratchBlob(name: String): ByteArray? {
        val path = scratchDir / name
        return if (fileSystem.exists(path)) fileSystem.read(path) { readByteArray() } else null
    }

    override suspend fun publish(scratch: DownloadScratch, fileName: String): String {
        val dest = outputDir / fileName
        val src = scratch.path.toPath()
        fileSystem.delete(dest, mustExist = false)
        // Rename within the same filesystem; fall back to copy+delete across mounts.
        runCatching { fileSystem.atomicMove(src, dest) }.onFailure {
            fileSystem.source(src).use { s -> fileSystem.sink(dest).buffer().use { it.writeAll(s) } }
            fileSystem.delete(src, mustExist = false)
        }
        return dest.toString()
    }
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
