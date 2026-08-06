package ymusic.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import ymusic.download.DefaultDownloadStorage
import java.io.File

/**
 * Android [DefaultDownloadStorage]: `file://` destinations (app dirs) go through okio as usual; a
 * user-picked SAF folder (`content://` tree, stored as the download-location bucket) is handled here by
 * creating a document and streaming the finished scratch file in via `ContentResolver`.
 */
class AndroidDownloadStorage(
    private val context: Context,
    fileSystem: FileSystem,
    scratchDir: Path,
) : DefaultDownloadStorage(fileSystem, scratchDir) {

    override suspend fun publishToUri(
        scratchPath: String,
        fileName: String,
        destinationFolder: kmpcommon.uri.Uri,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = Uri.parse(destinationFolder.toString()).let { uri ->
            // Defensive: accept a bare tree uri too, though the bucket stores a document uri.
            if (DocumentsContract.isDocumentUri(context, uri)) uri
            else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }
        val child = DocumentsContract.createDocument(resolver, parent, mimeFor(fileName), fileName)
            ?: error("SAF createDocument failed in $destinationFolder")
        resolver.openOutputStream(child)?.use { out ->
            File(scratchPath).inputStream().use { it.copyTo(out) }
        } ?: error("SAF openOutputStream failed for $child")
        child.toString()
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }
}
