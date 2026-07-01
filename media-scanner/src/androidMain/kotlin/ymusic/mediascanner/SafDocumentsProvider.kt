package ymusic.mediascanner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri as AndroidUri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Document.COLUMN_FLAGS
import android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
import android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
import android.provider.DocumentsContract.Document.COLUMN_SIZE
import android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
import android.provider.DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.mediascanner.internal.guessMimeType
import java.io.File

/**
 * A [DocumentsProvider] over Android's Storage Access Framework — the counterpart to the desktop
 * [FileSystemDocumentsProvider]. Handles SAF tree/document uris (`content://…/tree/…`) via
 * `DocumentsContract` + `ContentResolver`, and plain `file://` uris via [File]. The app is
 * responsible for obtaining and persisting the tree permission
 * (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`) and passing the granted tree uri.
 */
class SafDocumentsProvider(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentsProvider {

    private val projection = arrayOf(
        COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE,
        COLUMN_SIZE, COLUMN_LAST_MODIFIED, COLUMN_FLAGS,
    )

    override suspend fun queryDocument(uri: Uri): DocumentInfo = withContext(io) {
        val android = AndroidUri.parse(uri.toString())
        when (android.scheme) {
            "file" -> fileDocument(uri, File(requireNotNull(android.path)))
            "content" -> {
                if (!hasReadPermission(android)) return@withContext DocumentInfo.Missing(uri, permissionDenied = true)
                context.contentResolver.query(android, projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@withContext DocumentInfo.Missing(uri)
                    c.toDocumentInfo(uri)
                } ?: DocumentInfo.Missing(uri)
            }
            else -> DocumentInfo.Missing(uri)
        }
    }

    override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> = withContext(io) {
        val android = AndroidUri.parse(folderUri.toString())
        if (android.scheme == "file") {
            val dir = File(requireNotNull(android.path))
            if (!dir.canRead() || !dir.isDirectory) return@withContext emptyList()
            return@withContext dir.listFiles().orEmpty().map { child ->
                fileDocument(Uri.parse(AndroidUri.fromFile(child).toString()), child)
            }
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            android, DocumentsContract.getDocumentId(android),
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@withContext emptyList()
            val colId = c.getColumnIndexOrThrow(COLUMN_DOCUMENT_ID)
            val result = ArrayList<DocumentInfo>(c.count)
            do {
                val childDocId = c.getString(colId)
                val childAndroidUri = DocumentsContract.buildDocumentUriUsingTree(android, childDocId)
                result += c.toDocumentInfo(Uri.parse(childAndroidUri.toString()))
            } while (c.moveToNext())
            result
        } ?: emptyList()
    }

    private fun hasReadPermission(android: AndroidUri): Boolean = runCatching {
        context.checkCallingOrSelfUriPermission(android, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull() == PackageManager.PERMISSION_GRANTED

    private fun fileDocument(uri: Uri, file: File): DocumentInfo = when {
        !file.canRead() -> DocumentInfo.Missing(uri)
        file.isDirectory -> DocumentInfo.Folder(uri, name = file.name)
        else -> DocumentInfo.File(
            uri = uri,
            name = file.name,
            mimeType = guessMimeType(file.name),
            size = file.length(),
            lastModified = file.lastModified(),
        )
    }

    private fun Cursor.toDocumentInfo(uri: Uri): DocumentInfo {
        val name = getString(getColumnIndexOrThrow(COLUMN_DISPLAY_NAME))
        val mimeType = getString(getColumnIndexOrThrow(COLUMN_MIME_TYPE))
        val flags = runCatching { getInt(getColumnIndexOrThrow(COLUMN_FLAGS)) }.getOrDefault(0)
        val isVirtual = flags and FLAG_VIRTUAL_DOCUMENT != 0
        val isDirectory = mimeType == MIME_TYPE_DIR || flags and FLAG_DIR_SUPPORTS_CREATE != 0

        return if (isDirectory) {
            DocumentInfo.Folder(uri, name = name, isVirtual = isVirtual)
        } else {
            val sizeCol = getColumnIndexOrThrow(COLUMN_SIZE)
            val modCol = getColumnIndexOrThrow(COLUMN_LAST_MODIFIED)
            DocumentInfo.File(
                uri = uri,
                name = name,
                mimeType = mimeType ?: guessMimeType(name),
                size = if (isNull(sizeCol)) -1L else getLong(sizeCol),
                lastModified = if (isNull(modCol)) -1L else getLong(modCol),
                isVirtual = isVirtual,
            )
        }
    }
}
