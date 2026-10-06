package io.ymusic.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.ymusic.download.DefaultDownloadStorage
import io.ymusic.download.DownloadFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
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
        // SAF would rename a clash itself ("name (1).ext"); pick the app-wide "name_1.ext" instead.
        val existing = childNames(parent)
        val name = DownloadFileNames.freeName(fileName) { it in existing }
        val child = DocumentsContract.createDocument(resolver, parent, mimeFor(name), name)
            ?: error("SAF createDocument failed in $destinationFolder")
        resolver.openOutputStream(child)?.use { out ->
            File(scratchPath).inputStream().use { it.copyTo(out) }
        } ?: error("SAF openOutputStream failed for $child")
        child.toString()
    }

    private fun childNames(parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val names = mutableSetOf<String>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
        }
        return names
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
