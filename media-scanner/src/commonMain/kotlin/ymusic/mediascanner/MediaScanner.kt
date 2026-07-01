package ymusic.mediascanner

import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.Flow
import ymusic.mediascanner.internal.MediaScannerEngine
import ymusic.mediastore.BetterMediaStore

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
        val errors: Int = 0,
    )

    /**
     * Recursively scan the tree rooted at [rootUri] into the store, emitting running [Progress].
     * Per-file/folder failures are counted in [Progress.errors] and don't stop the scan; a fatal
     * failure and cancellation propagate to the collector. If [rootUri] isn't a readable folder
     * (e.g. on a no-op provider), the flow emits a single empty [Progress] and completes.
     */
    fun scan(rootUri: Uri): Flow<Progress>

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
