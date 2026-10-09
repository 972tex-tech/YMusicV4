package io.ymusic.dynamicengine

import kmpcommon.io.AsyncSource

/**
 * A **scoped** keyed blob cache the engine JS reads and writes over the `cache/…` pipe commands (the
 * bundle's `Cache` client). Every op names a **scope** — an independent partition with its own keys,
 * listing, and eviction — so "keep the last 10 home-feed items" and "a 100 MB download cache" live
 * side by side without interfering. Values stream to/from the store (never fully buffered); each entry
 * carries host-stamped [Meta] (size / updatedAt / lastUsed) plus caller-supplied [Meta.custom]
 * key-values, and **eviction policy lives entirely in the JS** (it uses [list] + `delete`). Ship
 * [DiskCacheStore] (metadata indexed in SQLite, blobs on the filesystem); pass it to [DynamicEngine.create].
 *
 * Deliberately NOT a filesystem: keyed within a scope, no paths, no seek/append.
 *
 * Guarantees every implementation must keep:
 * - **No partial values.** A [put] commits only after [put]'s body reaches EOF; a body that fails or is
 *   cancelled first leaves the previous value (or the miss) untouched.
 * - **Keys never wait on each other.** A body may stay open indefinitely (a whole player session), so
 *   no lock is held while one streams; a slow put only delays itself.
 * - **The last to start wins.** A [put] or [delete] of a scope+key cancels that key's in-flight put,
 *   which fails `E20053` (as does one done streaming but overtaken before it commits).
 */
interface CacheStore {
    /** The entry for [key] in [scope] — its metadata plus a streaming value handle — or null on a
     *  miss. Reading **bumps `lastUsed`** (so an LRU evictor sees the access). */
    suspend fun get(scope: String, key: String): Entry?

    /** Store [body] (streamed to EOF) under [scope]/[key] with [custom] metadata; returns the [Meta].
     *  `updatedAt` and `lastUsed` are both set to now; `size` is the byte count. */
    suspend fun put(scope: String, key: String, custom: Map<String, String>, body: AsyncSource): Meta

    suspend fun delete(scope: String, key: String)

    /** Metadata for every key in [scope] (no values) — what the JS eviction policy reads. */
    suspend fun list(scope: String): List<Meta>

    /** Host-stamped metadata. [size] is the value's byte length; [updatedAt] is the last write and
     *  [lastUsed] the last access (both epoch millis); [custom] is the caller's opaque map. */
    data class Meta(
        val key: String,
        val size: Long,
        val updatedAt: Long,
        val lastUsed: Long,
        val custom: Map<String, String>,
    )

    interface Entry {
        val meta: Meta
        /** Open a fresh stream over the value; the caller closes it. */
        fun open(): AsyncSource

        /** Local filesystem path of the blob, if this store is file-backed. */
        val filePath: String? get() = null
    }
}
