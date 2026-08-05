package ymusic.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.download.ContentPublisher
import java.io.File

/**
 * Publishes a finished download into a user-picked SAF folder (`content://` tree). [folderUri] is the
 * folder's document uri stored as the download-location bucket (see `SafDocumentsProvider.queryDocument`).
 * Creates a document there and streams the local scratch file in via `ContentResolver`.
 */
class SafContentPublisher(private val context: Context) : ContentPublisher {

    override suspend fun publish(scratchPath: String, folderUri: String, fileName: String): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent = Uri.parse(folderUri).let { uri ->
                // Defensive: accept a bare tree uri too, though the bucket stores a document uri.
                if (DocumentsContract.isDocumentUri(context, uri)) uri
                else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            }
            val child = DocumentsContract.createDocument(resolver, parent, mimeFor(fileName), fileName)
                ?: error("SAF createDocument failed in $folderUri")
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
