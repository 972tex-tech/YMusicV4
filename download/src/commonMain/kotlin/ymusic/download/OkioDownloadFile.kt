package ymusic.download

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import ymusic.ytmedia.sabr.DownloadFile

/**
 * Multiplatform [DownloadFile] over an okio [FileSystem]. Append-only: [appendSink] opens an appending
 * sink at the current EOF and [length] reads the on-disk size, so a re-run resumes where it left off
 * (the SABR loop reads [length] as its resume point). The JVM-only `FileDownloadFile` in the download
 * probe is the reference; this is its commonMain equivalent.
 */
class OkioDownloadFile(
    private val fileSystem: FileSystem,
    private val path: Path,
) : DownloadFile {

    init {
        path.parent?.let { fileSystem.createDirectories(it) }
    }

    override fun length(): Long =
        if (fileSystem.exists(path)) fileSystem.metadata(path).size ?: 0L else 0L

    override fun appendSink(): BufferedSink =
        fileSystem.appendingSink(path).buffer()

    override fun close() {}
}
