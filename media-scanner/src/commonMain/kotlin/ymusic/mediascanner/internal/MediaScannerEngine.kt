package ymusic.mediascanner.internal

import kmpcommon.uri.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ymusic.mediascanner.DocumentInfo
import ymusic.mediascanner.DocumentsProvider
import ymusic.mediascanner.MediaMetadataExtractor
import ymusic.mediascanner.MediaScanner
import ymusic.mediascanner.MediaScanner.Progress
import ymusic.mediascanner.MediaScanner.ScanError
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.BucketDraft

/**
 * The scan engine. Traverses the tree concurrently with a [Semaphore]-bounded worker pool (the heavy
 * work — listing a folder and probing a file — runs under a permit), reconciling each folder against
 * the store. Structured concurrency makes the whole scan cancelable via the collector.
 */
internal class MediaScannerEngine(
    private val store: BetterMediaStore,
    private val documents: DocumentsProvider,
    private val metadata: MediaMetadataExtractor,
    private val concurrency: Int,
) : MediaScanner {

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, was $concurrency" }
    }

    // One worker-permit pool shared across ALL scans on this engine: N concurrent scans still run at
    // most `concurrency` heavy operations (directory listing / ffmpeg probe) in total, so the load on
    // the shared resource (CPU/disk/ffmpeg processes) stays bounded no matter how many scans overlap.
    private val semaphore = Semaphore(concurrency)

    override fun scan(rootUri: Uri): Flow<Progress> = channelFlow {
        val progress = MutableStateFlow(Progress())
        // Forward every progress change to the collector (StateFlow conflates rapid updates).
        val forward = launch { progress.collect { send(it) } }
        try {
            when (val root = documents.queryDocument(rootUri)) {
                is DocumentInfo.Folder -> {
                    // Preserve the root's existing parent (if it's already a known sub-folder) so scanning
                    // a sub-folder directly doesn't orphan it — otherwise upsertBucket would reset parentId
                    // to null. A genuinely new root has no existing row, so parentId stays null.
                    val rootParentId = store.findBucketByUri(root.uri)?.parentId
                    // coroutineScope joins the whole worker tree; an uncaught (fatal) child cancels it
                    // and propagates, while recoverable per-node errors are caught below and counted.
                    coroutineScope {
                        launchFolder(this, root, parentBucketId = rootParentId, progress)
                    }
                }
                // A single media file: index it directly (as an orphan until a folder scan adopts it).
                is DocumentInfo.File -> scanFile(root, progress)
                is DocumentInfo.Missing -> {} // nothing to scan (also the no-op provider's path)
            }
            forward.cancelAndJoin() // stop the forwarder before the authoritative final send
            send(progress.value) // guaranteed final snapshot (also the empty terminal for a no-op root)
        } catch (e: Throwable) {
            forward.cancel()
            throw e
        }
    }

    /**
     * Index one media file: same uri unchanged → skip; same uri changed → update in place (keeps its
     * bucket via `upsertItem(null)`); a new uri → insert as an orphan (adopted later by a folder scan).
     * A moved file (new uri) becomes a fresh orphan; the stale old row is pruned when its folder is
     * rescanned. Playlist continuity across moves is the job of the content-addressed playlist code,
     * not of tracking the row here.
     */
    private suspend fun scanFile(file: DocumentInfo.File, progress: MutableStateFlow<Progress>) {
        if (file.isVirtual || file.size <= 0 || !isMediaFile(file.name, file.mimeType)) return
        try {
            val known = store.findItemByUri(file.uri)
            if (known != null && known.fileSize == file.size && known.lastModified == file.lastModified) {
                progress.update { it.copy(scanned = it.scanned + 1) } // unchanged
                return
            }
            val draft = semaphore.withPermit { metadata.extract(file) }
            if (draft == null) {
                progress.update { it.copy(scanned = it.scanned + 1) } // couldn't probe; skip
                return
            }
            store.upsertItem(bucketId = null, draft) // orphan if new; keeps bucket if already filed
            progress.update {
                if (known == null) it.copy(scanned = it.scanned + 1, added = it.added + 1)
                else it.copy(scanned = it.scanned + 1, updated = it.updated + 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            progress.update { it.copy(errors = it.errors + ScanError(file.uri, e)) }
        }
    }

    private fun launchFolder(
        scope: CoroutineScope,
        folder: DocumentInfo.Folder,
        parentBucketId: Long?,
        progress: MutableStateFlow<Progress>,
    ) {
        scope.launch {
            try {
                val bucket = store.upsertBucket(
                    BucketDraft(uri = folder.uri, name = folder.name, parentId = parentBucketId)
                )
                val children = semaphore.withPermit { documents.listChildren(folder.uri) }

                val subFolders = children.filterIsInstance<DocumentInfo.Folder>()
                    .filter { !it.isVirtual && !it.name.startsWith(".") }
                val mediaFiles = children.filterIsInstance<DocumentInfo.File>()
                    .filter { !it.isVirtual && it.size > 0 && isMediaFile(it.name, it.mimeType) }

                reconcileItems(scope, bucket.id, mediaFiles, progress)

                // Soft-delete child buckets that are no longer present (cascades their items).
                val presentFolderUris = subFolders.mapTo(HashSet()) { it.uri }
                store.childBuckets(bucket.id).forEach { existing ->
                    if (existing.uri !in presentFolderUris) {
                        store.deleteBucket(existing.id)
                        progress.update { it.copy(deleted = it.deleted + 1) }
                    }
                }

                subFolders.forEach { launchFolder(scope, it, bucket.id, progress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ensureActive() // don't swallow cancellation; other Throwables are recoverable
                progress.update { it.copy(errors = it.errors + ScanError(folder.uri, e)) }
            }
        }
    }

    private suspend fun reconcileItems(
        scope: CoroutineScope,
        bucketId: Long,
        mediaFiles: List<DocumentInfo.File>,
        progress: MutableStateFlow<Progress>,
    ) {
        val existing = store.itemsInBucket(bucketId)
        val byUri = existing.associateBy { it.uri }
        val presentUris = mediaFiles.mapTo(HashSet()) { it.uri }

        // Soft-delete items whose file has vanished from this folder.
        existing.forEach { item ->
            if (item.uri !in presentUris) {
                store.deleteItem(item.id)
                progress.update { it.copy(deleted = it.deleted + 1) }
            }
        }

        // Probe + upsert present files concurrently (each probe under a worker permit).
        mediaFiles.forEach { file ->
            scope.launch {
                try {
                    val known = byUri[file.uri]
                    if (known != null &&
                        known.fileSize == file.size &&
                        known.lastModified == file.lastModified
                    ) {
                        progress.update { it.copy(scanned = it.scanned + 1) } // unchanged
                        return@launch
                    }
                    val draft = semaphore.withPermit { metadata.extract(file) }
                    if (draft == null) {
                        progress.update { it.copy(scanned = it.scanned + 1) } // couldn't probe; skip
                        return@launch
                    }
                    store.upsertItem(bucketId, draft)
                    progress.update {
                        if (known == null) it.copy(scanned = it.scanned + 1, added = it.added + 1)
                        else it.copy(scanned = it.scanned + 1, updated = it.updated + 1)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ensureActive()
                    progress.update { it.copy(errors = it.errors + ScanError(file.uri, e)) }
                }
            }
        }
    }
}
