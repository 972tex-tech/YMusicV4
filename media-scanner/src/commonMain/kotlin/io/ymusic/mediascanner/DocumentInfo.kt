package io.ymusic.mediascanner

import kmpcommon.uri.Uri

/**
 * The cross-platform form of a browsable document — modeled on an Android `DocumentsContract` row so
 * the SAF implementation maps 1:1 and simpler backends (filesystem) just fill the same fields.
 */
sealed interface DocumentInfo {
    val uri: Uri

    /** A directory that can be listed with [DocumentsProvider.listChildren]. */
    data class Folder(
        override val uri: Uri,
        val name: String,
        val isVirtual: Boolean = false,
    ) : DocumentInfo

    /** A file with its stat. [size]/[lastModified] are the change fingerprint used against the store. */
    data class File(
        override val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long,
        val isVirtual: Boolean = false,
    ) : DocumentInfo

    /** The uri does not resolve — gone, not a document, or access was denied. */
    data class Missing(
        override val uri: Uri,
        val permissionDenied: Boolean = false,
    ) : DocumentInfo
}
