package ymusic.mediascanner

import kmpcommon.uri.Uri

/**
 * Browse a document tree: stat a node, list a folder's children. The Android actual is the Storage
 * Access Framework (`DocumentsContract` over `ContentResolver`); desktop uses the filesystem
 * ([FileSystemDocumentsProvider]); on platforms with no scanning support the app supplies
 * [NoopDocumentsProvider].
 *
 * Reading a document's *bytes* is intentionally not here — metadata extraction reads the source
 * itself (via kmpFfmpeg's input resolver), so a provider only needs navigation + stat.
 */
interface DocumentsProvider {
    /** Stat a single document uri; returns [DocumentInfo.Missing] if it is absent or unreadable. */
    suspend fun queryDocument(uri: Uri): DocumentInfo

    /** Direct children of a folder uri; empty if the uri is not a readable folder. */
    suspend fun listChildren(folderUri: Uri): List<DocumentInfo>
}

/** A provider that reports every uri [DocumentInfo.Missing] — used where scanning is unsupported (iOS). */
object NoopDocumentsProvider : DocumentsProvider {
    override suspend fun queryDocument(uri: Uri): DocumentInfo = DocumentInfo.Missing(uri)
    override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> = emptyList()
}
