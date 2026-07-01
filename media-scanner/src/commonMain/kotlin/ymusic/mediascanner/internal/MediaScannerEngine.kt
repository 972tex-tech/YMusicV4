package ymusic.mediascanner.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kmpcommon.uri.Uri
import ymusic.mediascanner.DocumentInfo
import ymusic.mediascanner.DocumentsProvider
import ymusic.mediascanner.MediaMetadataExtractor
import ymusic.mediascanner.MediaScanner
import ymusic.mediascanner.MediaScanner.Progress
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

    override fun scan(rootUri: Uri): Flow<Progress> = channelFlow {
        val progress = MutableStateFlow(Progress())
        // Forward every progress change to the collector (StateFlow conflates rapid updates).
        val forward = launch { progress.collect { send(it) } }
        try {
            val root = documents.queryDocument(rootUri)
            if (root is DocumentInfo.Folder) {
                val semaphore = Semaphore(concurrency)
                // coroutineScope joins the whole worker tree; an uncaught (fatal) child cancels it
                // and propagates, while recoverable per-node errors are caught below and counted.
                coroutineScope {
                    launchFolder(this, root, parentBucketId = null, semaphore, progress)
                }
            }
            forward.cancelAndJoin() // stop the forwarder before the authoritative final send
            send(progress.value) // guaranteed final snapshot (also the empty terminal for a no-op root)
        } catch (e: Throwable) {
            forward.cancel()
            throw e
        }
    }

    private fun launchFolder(
        scope: CoroutineScope,
        folder: DocumentInfo.Folder,
        parentBucketId: Long?,
        semaphore: Semaphore,
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

                reconcileItems(scope, bucket.id, mediaFiles, semaphore, progress)

                // Soft-delete child buckets that are no longer present (cascades their items).
                val presentFolderUris = subFolders.mapTo(HashSet()) { it.uri }
                store.childBuckets(bucket.id).forEach { existing ->
                    if (existing.uri !in presentFolderUris) {
                        store.deleteBucket(existing.id)
                        progress.update { it.copy(deleted = it.deleted + 1) }
                    }
                }

                subFolders.forEach { launchFolder(scope, it, bucket.id, semaphore, progress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ensureActive() // don't swallow cancellation; other Throwables are recoverable
                progress.update { it.copy(errors = it.errors + 1) }
            }
        }
    }

    private suspend fun reconcileItems(
        scope: CoroutineScope,
        bucketId: Long,
        mediaFiles: List<DocumentInfo.File>,
        semaphore: Semaphore,
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
                    progress.update { it.copy(errors = it.errors + 1) }
                }
            }
        }
    }
}
