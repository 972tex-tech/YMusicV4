package io.ymusic.mediascanner

import io.ymusic.mediascanner.internal.MediaScannerEngine
import io.ymusic.mediastore.BetterMediaStore
import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Walks a document tree, reconciling it into a [BetterMediaStore]: new/changed files are probed for
 * metadata and upserted, vanished files/folders are soft-deleted. Storage-agnostic — it drives a
 * [DocumentsProvider] (Android SAF, desktop filesystem, or a no-op) and a [MediaMetadataExtractor].
 *
 * Scanning is concurrent (a bounded pool of workers) and fully cancelable: cancelling the collector
 * of [scan]'s flow stops the scan at the next suspension point.
 */
interface MediaScanner {
    /** Running totals emitted over the course of a scan. */
    data class Progress(
        val scanned: Int = 0,
        val added: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        /** Per-node failures that didn't stop the scan — each keeps the uri that failed and its cause. */
        val errors: List<ScanError> = emptyList(),
    )

    /** A recoverable failure while scanning [uri]; [cause] is the exception that was caught, not dropped. */
    data class ScanError(val uri: Uri, val cause: Throwable)

    /**
     * Scan [uri] into the store, emitting running [Progress]. [uri] may be any directory, not only a
     * top-level root (a sub-folder can be scanned directly):
     *  - a **folder** is reconciled recursively (new/changed files upserted, vanished ones soft-deleted);
     *  - a single **media file** is indexed on its own — filed under [intoBucket] when given (a completed
     *    download knows the folder bucket it lives in), else as an orphan (no bucket) until a later folder
     *    scan adopts it; re-scanning it never duplicates it;
     *  - an **excluded** folder is left untouched (scanning it would revive what exclusion removed);
     *  - anything else (unreadable / not media / a no-op provider) emits one empty [Progress].
     *
     * [intoBucket] applies only to the single-file case; a folder scan files items under the folders it
     * walks. Per-file/folder failures are collected in [Progress.errors] (each with its uri and cause) and
     * don't stop the scan; a fatal failure and cancellation propagate to the collector.
     */
    fun scan(uri: Uri, intoBucket: Long? = null): Flow<Progress>

    companion object {
        /** Default number of concurrent scan workers. */
        const val DEFAULT_CONCURRENCY = 4

        fun create(
            store: BetterMediaStore,
            documents: DocumentsProvider,
            metadata: MediaMetadataExtractor,
            concurrency: Int = DEFAULT_CONCURRENCY,
        ): MediaScanner = MediaScannerEngine(store, documents, metadata, concurrency)
    }
}
