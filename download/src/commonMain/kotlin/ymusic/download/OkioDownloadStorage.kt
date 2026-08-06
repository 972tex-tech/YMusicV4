package ymusic.download

import kmpcommon.error.TechnicalException
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
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

    override fun scratch(taskId: String, name: String): DownloadScratch =
        OkioDownloadScratch(fileSystem, scratchDir / taskId / name)

    override fun deleteScratch(taskId: String) {
        fileSystem.deleteRecursively(scratchDir / taskId, mustExist = false)
    }

    override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String?): String {
        // A content:// (or any non-file) folder can't be an okio path — delegate to the platform publisher.
        if (destinationFolder != null && !destinationFolder.startsWith("file:")) {
            val publisher = contentPublisher
                ?: throw TechnicalException("E70031", "no ContentPublisher for destination $destinationFolder")
            val uri = try {
                publisher.publish(scratch.path, destinationFolder, fileName)
            } catch (e: TechnicalException) {
                throw e
            } catch (e: Exception) {
                throw TechnicalException("E70032", "could not publish $fileName into $destinationFolder", cause = e)
            }
            scratch.delete()
            return uri
        }
        val dir = destinationFolder?.let { fileUriToPath(it) } ?: outputDir
        val dest = dir / fileName
        val src = scratch.path.toPath()
        try {
            fileSystem.createDirectories(dir)
            fileSystem.delete(dest, mustExist = false)
            // A same-filesystem rename is atomic and cheap; a cross-mount one can't be, and THAT is the only
            // failure a copy legitimately recovers. okio surfaces every atomicMove failure as a bare
            // IOException with no portable errno, so we can't tell "cross-device" from "no space / no
            // permission / source gone" by inspecting it — we attempt the rename, and on any IOException try
            // the copy. If the copy also fails it's a genuine storage error, surfaced coded below (not as a
            // second bare okio throw).
            try {
                fileSystem.atomicMove(src, dest)
            } catch (_: IOException) {
                fileSystem.source(src).use { s -> fileSystem.sink(dest).buffer().use { it.writeAll(s) } }
                fileSystem.delete(src, mustExist = false)
            }
        } catch (e: TechnicalException) {
            throw e
        } catch (e: Exception) {
            throw TechnicalException("E70030", "could not publish $fileName into $dir", cause = e)
        }
        return "file://$dest" // a real uri, like the content:// path — so task.fileUri is always parseable
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
