package ymusic.download

import kmpcommon.error.TechnicalException
import kmpcommon.uri.Uri
import kmpcommon.uri.fileUri
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * [DownloadStorage] over an okio [FileSystem]. Scratch files live under [scratchDir]; [publish] moves the
 * finished file into a `file://` destination folder. Non-`file://` destinations (e.g. Android SAF
 * `content://`) are delegated to [publishToUri], which the base rejects — a platform subclass
 * (`AndroidDownloadStorage`) overrides it. Used directly on desktop/iOS, where destinations are `file://`.
 */
open class DefaultDownloadStorage(
    private val fileSystem: FileSystem,
    private val scratchDir: Path,
) : DownloadStorage {

    init {
        fileSystem.createDirectories(scratchDir)
    }

    override fun scratch(taskId: String, name: String): DownloadScratch =
        DefaultDownloadScratch(fileSystem, scratchDir / taskId / name)

    override fun deleteScratch(taskId: String) {
        fileSystem.deleteRecursively(scratchDir / taskId, mustExist = false)
    }

    final override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String {
        val folder = Uri.parse(destinationFolder)
        if (folder.scheme != "file") {
            val uri = try {
                publishToUri(scratch.path, fileName, folder)
            } catch (e: TechnicalException) {
                throw e
            } catch (e: Exception) {
                throw TechnicalException("E70032", "could not publish $fileName into $destinationFolder", cause = e)
            }
            scratch.delete()
            return uri
        }
        val dir = folder.path.toString().toPath()
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
        // Build the uri (percent-encoding the path) rather than "file://$dest" — a name with a space or
        // '#' would otherwise produce a malformed string that won't round-trip or match the scanned uri.
        return fileUri(dest.toString()).toString()
    }

    /**
     * Publish [scratchPath]'s finished bytes into a non-`file://` [destinationFolder] as [fileName] and
     * return the created file's uri. The base implementation rejects any such scheme; a platform subclass
     * that can reach the folder (Android SAF `content://`) overrides this.
     */
    protected open suspend fun publishToUri(scratchPath: String, fileName: String, destinationFolder: Uri): String =
        throw TechnicalException(
            "E70031",
            "destination scheme '${destinationFolder.scheme}' unsupported by ${this::class.simpleName}",
        )
}

/** okio-backed [DownloadScratch]: append-only writes (resume via on-disk size) + a real path for ffmpeg. */
private class DefaultDownloadScratch(
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
