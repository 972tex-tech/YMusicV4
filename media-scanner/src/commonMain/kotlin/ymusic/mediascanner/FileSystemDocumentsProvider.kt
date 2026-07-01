package ymusic.mediascanner

import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import ymusic.mediascanner.internal.guessMimeType

/**
 * A [DocumentsProvider] over an okio [FileSystem]. Works on every platform okio supports (JVM,
 * native Linux/macOS, Apple); desktop apps construct it with `FileSystem.SYSTEM`, tests with a
 * `FakeFileSystem`. Handles `file:` uris (path taken from the uri's decoded path).
 */
class FileSystemDocumentsProvider(
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : DocumentsProvider {

    override suspend fun queryDocument(uri: Uri): DocumentInfo = withContext(io) {
        val path = uri.path.toString().toPath()
        val md = fileSystem.metadataOrNull(path) ?: return@withContext DocumentInfo.Missing(uri)
        when {
            md.isDirectory -> DocumentInfo.Folder(uri, name = path.name)
            else -> DocumentInfo.File(
                uri = uri,
                name = path.name,
                mimeType = guessMimeType(path.name),
                size = md.size ?: -1L,
                lastModified = md.lastModifiedAtMillis ?: -1L,
            )
        }
    }

    override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> = withContext(io) {
        val dir = folderUri.path.toString().toPath()
        val children = runCatching { fileSystem.list(dir) }.getOrNull() ?: return@withContext emptyList()
        children.map { child ->
            val childUri = Uri.parse("file://$child")
            val md = fileSystem.metadataOrNull(child)
            when {
                md == null -> DocumentInfo.Missing(childUri)
                md.isDirectory -> DocumentInfo.Folder(childUri, name = child.name)
                else -> DocumentInfo.File(
                    uri = childUri,
                    name = child.name,
                    mimeType = guessMimeType(child.name),
                    size = md.size ?: -1L,
                    lastModified = md.lastModifiedAtMillis ?: -1L,
                )
            }
        }
    }
}
