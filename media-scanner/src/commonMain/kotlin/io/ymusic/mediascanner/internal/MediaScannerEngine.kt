package io.ymusic.mediascanner.internal

import io.ymusic.mediascanner.DocumentInfo
import io.ymusic.mediascanner.DocumentsProvider
import io.ymusic.mediascanner.MediaMetadataExtractor
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediascanner.MediaScanner.Progress
import io.ymusic.mediascanner.MediaScanner.ScanError
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.BucketDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The scan engine. Traverses the tree concurrently with a [Semaphore]-bounded worker pool (the heavy
 * work — listing a folder and probing a file — runs under a permit), reconciling each folder against
 * the store. Structured concurrency makes the whole scan cancelable via the collector.
 *
 * **Media-less folders are not indexed.** A bucket row is created only when media is found at or
 * below that folder (via [LazyBucket], which materializes lazily up the ancestor chain), so scanning
 * a folder like Downloads that holds a handful of songs among thousands of code/app directories
 * indexes only the folders that actually contain music. The scan **root** is the one exception — it
 * is always kept, even when empty. A folder that used to hold media but no longer does is pruned on
 * rescan (its bucket deleted, cascading its subtree); a folder we could not *read* (an error) is
 * never pruned — status quo wins over a false "empty".
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

    /**
     * A folder's bucket, created on first [idOrCreate] call and only then — so a folder with no media at or
     * below it never gets a row. Asking for a child's id materializes its whole ancestor chain, which
     * is exactly the set of folders on the path to some media. [materialized] tells the caller after
     * the fact whether the folder ended up with a row (i.e. had media somewhere below it).
     */
    private inner class LazyBucket(
        private val uri: Uri,
        private val name: String,
        private val parent: LazyBucket?,
        private val rootParentId: Long?,
    ) {
        private val mutex = Mutex()
        private var cachedId: Long? = null
        var materialized = false
            private set

        suspend fun idOrCreate(): Long {
            cachedId?.let { return it }
            return mutex.withLock {
                cachedId?.let { return it }
                val parentId = parent?.idOrCreate() ?: rootParentId
                val bucket = store.upsertBucket(BucketDraft(uri = uri, name = name, parentId = parentId))
                cachedId = bucket.id
                materialized = true
                bucket.id
            }
        }
    }

    /** What a folder's scan concluded, for its parent's prune decision: [hasMedia] = media at/below
     *  (so its bucket exists and must be kept); [hadError] = something in the subtree could not be
     *  read (so the parent must NOT prune it, even if it currently looks media-less). */
    private data class FolderScan(val hasMedia: Boolean, val hadError: Boolean)

    override fun scan(uri: Uri, intoBucket: Long?): Flow<Progress> = channelFlow {
        val progress = MutableStateFlow(Progress())
        // Forward every progress change to the collector (StateFlow conflates rapid updates).
        val forward = launch { progress.collect { send(it) } }
        try {
            when (val folder = documents.queryDocument(uri)) {
                is DocumentInfo.Folder -> {
                    val existingEntry = store.findBucketByUri(folder.uri)
                    if (existingEntry?.excluded != true) {
                        coroutineScope {
                            val folderBucket = LazyBucket(folder.uri, folder.name, parent = null, existingEntry?.parentId)
                            folderBucket.idOrCreate() // the scanned folder is always kept, even when empty
                            scanFolder(this, folder, folderBucket, progress, store.excludedBucketUris())
                        }
                    }
                }
                // A single media file: index it directly — into [intoBucket] if the caller named one, else
                // as an orphan until a folder scan adopts it.
                is DocumentInfo.File -> scanFile(folder, progress, intoBucket)
                // Gone or no longer readable: a known folder's rows become tombstones (a later pick revives them).
                is DocumentInfo.Missing -> store.findBucketByUri(folder.uri)?.let { bucket ->
                    if (!bucket.excluded) {
                        store.deleteBucket(bucket.id)
                        progress.update { it.copy(deleted = it.deleted + 1) }
                    }
                }
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
     */
    private suspend fun scanFile(file: DocumentInfo.File, progress: MutableStateFlow<Progress>, intoBucket: Long? = null) {
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
            // Orphan a new file (bucketId=null) unless a caller named the bucket it lives in (a completed
            // download knows its download-location folder); an existing item keeps its bucket via null.
            store.upsertItem(bucketId = intoBucket, draft)
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

    /**
     * Reconcile [folder] against the store, recursing concurrently. [self] is materialized (its bucket
     * created) only if this folder holds media directly or a descendant does. Returns whether media
     * was found (so the parent keeps the bucket) and whether the subtree hit a read error.
     */
    private suspend fun scanFolder(
        scope: CoroutineScope,
        folder: DocumentInfo.Folder,
        self: LazyBucket,
        progress: MutableStateFlow<Progress>,
        excludedUris: Set<Uri>,
    ): FolderScan {
        val children = try {
            semaphore.withPermit { documents.listChildren(folder.uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            progress.update { it.copy(errors = it.errors + ScanError(folder.uri, e)) }
            // Could not read this folder — don't let its (unknown) contents be pruned.
            return FolderScan(hasMedia = self.materialized, hadError = true)
        }

        val subFolders = children.filterIsInstance<DocumentInfo.Folder>()
            .filter { !it.isVirtual && !it.name.startsWith(".") }
        val mediaFiles = children.filterIsInstance<DocumentInfo.File>()
            .filter { !it.isVirtual && it.size > 0 && isMediaFile(it.name, it.mimeType) }
        val presentSubUris = subFolders.mapTo(HashSet()) { it.uri }

        val childResults = HashMap<Uri, FolderScan>()
        coroutineScope {
            // Present media materializes this folder (and its ancestors) and reconciles its items.
            if (mediaFiles.isNotEmpty()) {
                launch { reconcileItems(this@coroutineScope, self.idOrCreate(), mediaFiles, progress) }
            }
            subFolders.map { sub ->
                async {
                    if (sub.uri in excludedUris) {
                        // Excluded subtree: don't descend, reconcile, or prune it — but materialize
                        // this folder so it (and its ancestors) survive an ancestor's prune, which
                        // would otherwise cascade the excluded subtree away.
                        self.idOrCreate()
                        sub.uri to FolderScan(hasMedia = true, hadError = false)
                    } else {
                        val subBucket = LazyBucket(sub.uri, sub.name, parent = self, rootParentId = null)
                        sub.uri to scanFolder(this@coroutineScope, sub, subBucket, progress, excludedUris)
                    }
                }
            }.awaitAll().forEach { (uri, result) -> childResults[uri] = result }
        }
        val subtreeError = childResults.values.any { it.hadError }

        // Materialized but no direct media now → drop this folder's stale items (moved/removed).
        if (self.materialized && mediaFiles.isEmpty()) {
            reconcileItems(scope, self.idOrCreate(), emptyList(), progress)
        }

        // Prune child buckets we can account for: vanished from disk, or scanned clean with no media.
        // Never prune a child whose subtree hit an error (we can't confirm it's empty).
        if (self.materialized) {
            store.childBuckets(self.idOrCreate()).forEach { child ->
                val result = childResults[child.uri]
                val keep = when {
                    child.uri !in presentSubUris -> false // vanished
                    result == null -> true // shouldn't happen; keep to be safe
                    result.hasMedia -> true
                    result.hadError -> true
                    else -> false // read clean, no media at/below → prune (cascades its subtree)
                }
                if (!keep) {
                    store.deleteBucket(child.id)
                    progress.update { it.copy(deleted = it.deleted + 1) }
                }
            }
        }

        return FolderScan(hasMedia = self.materialized, hadError = subtreeError)
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
