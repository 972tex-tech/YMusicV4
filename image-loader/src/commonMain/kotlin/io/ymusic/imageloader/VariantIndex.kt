package io.ymusic.imageloader

import kmphttp.cache.DiskLruCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * What the loader has learned about one image identity's sources, keyed by [imageCacheKey] of each
 * source url: the size it actually decoded at (after crop — declared sizes can be wrong), and the
 * sources that answered NotFound. Lets a request prefer a variant it already fetched (on disk in the
 * fetcher's own cache) and skip known-missing ones across restarts.
 */
interface VariantIndex {
    suspend fun get(key: String): VariantRecord

    suspend fun recordFetched(key: String, sourceKey: String, width: Int, height: Int)

    suspend fun recordMissing(key: String, sourceKey: String)
}

class VariantRecord(
    /** sourceKey → measured (width, height). */
    val fetched: Map<String, Pair<Int, Int>>,
    /** sourceKeys that answered NotFound recently enough to still trust. */
    val missing: Set<String>,
) {
    companion object {
        val EMPTY = VariantRecord(emptyMap(), emptySet())
    }
}

/** A process-lifetime [VariantIndex] (the default): learns within a session, forgets on restart. */
class MemoryVariantIndex(private val missingTtl: Duration = 3.days) : VariantIndex {
    private val lock = SynchronizedObject()
    private val entries = HashMap<String, Entry>()

    private class Entry(val fetched: HashMap<String, Pair<Int, Int>> = HashMap(), val missing: HashMap<String, Long> = HashMap())

    override suspend fun get(key: String): VariantRecord = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized VariantRecord.EMPTY
        val cutoff = nowMillis() - missingTtl.inWholeMilliseconds
        VariantRecord(HashMap(entry.fetched), entry.missing.filterValues { it >= cutoff }.keys.toSet())
    }

    override suspend fun recordFetched(key: String, sourceKey: String, width: Int, height: Int): Unit = synchronized(lock) {
        val entry = entries.getOrPut(key) { Entry() }
        entry.fetched[sourceKey] = width to height
        entry.missing.remove(sourceKey)
    }

    override suspend fun recordMissing(key: String, sourceKey: String): Unit = synchronized(lock) {
        val entry = entries.getOrPut(key) { Entry() }
        entry.missing[sourceKey] = nowMillis()
        entry.fetched.remove(sourceKey)
    }
}

/**
 * [VariantIndex] persisted in a journaled [DiskLruCache] under [directory]: one small text entry per
 * identity (`ok <sourceKey> <w> <h>` / `miss <sourceKey> <epochMillis>` lines), LRU-bounded to
 * [maxBytes], with recently used entries mirrored in memory. A recorded variant whose file the
 * fetcher's cache has since evicted just costs a refetch.
 */
class DiskVariantIndex(
    private val fileSystem: FileSystem,
    directory: Path,
    maxBytes: Long = 2L * 1024 * 1024,
    private val missingTtl: Duration = 3.days,
) : VariantIndex {
    private val cache = DiskLruCache(fileSystem = fileSystem, directory = directory, valueCount = 1, maxSize = maxBytes)
    private val memory = MemoryVariantIndex(missingTtl)
    private val lock = SynchronizedObject()
    private val loaded = LinkedHashMap<String, Boolean>()

    override suspend fun get(key: String): VariantRecord {
        ensureLoaded(key)
        return memory.get(key)
    }

    override suspend fun recordFetched(key: String, sourceKey: String, width: Int, height: Int) {
        ensureLoaded(key)
        memory.recordFetched(key, sourceKey, width, height)
        persist(key)
    }

    override suspend fun recordMissing(key: String, sourceKey: String) {
        ensureLoaded(key)
        memory.recordMissing(key, sourceKey)
        persist(key)
    }

    private suspend fun ensureLoaded(key: String) {
        if (synchronized(lock) { loaded.containsKey(key) }) return
        val lines = withContext(Dispatchers.IO) {
            cache.get(diskKey(key))?.use { snapshot ->
                fileSystem.source(snapshot.getPath(0)).buffer().use { it.readUtf8() }.lines()
            }
        }.orEmpty()
        val cutoff = nowMillis() - missingTtl.inWholeMilliseconds
        for (line in lines) {
            val parts = line.split(' ')
            when {
                parts.size == 4 && parts[0] == "ok" -> {
                    val w = parts[2].toIntOrNull() ?: continue
                    val h = parts[3].toIntOrNull() ?: continue
                    memory.recordFetched(key, parts[1], w, h)
                }
                parts.size == 3 && parts[0] == "miss" -> {
                    val at = parts[2].toLongOrNull() ?: continue
                    if (at >= cutoff) memory.recordMissing(key, parts[1])
                }
            }
        }
        synchronized(lock) {
            loaded[key] = true
            // Bound the "already read from disk" set; a dropped key just re-reads its entry once.
            if (loaded.size > LOADED_LIMIT) loaded.remove(loaded.keys.first())
        }
    }

    private suspend fun persist(key: String) {
        val record = memory.get(key)
        val text = buildString {
            record.fetched.forEach { (source, size) -> append("ok ").append(source).append(' ').append(size.first).append(' ').append(size.second).append('\n') }
            val now = nowMillis()
            record.missing.forEach { source -> append("miss ").append(source).append(' ').append(now).append('\n') }
        }
        withContext(Dispatchers.IO) {
            val editor = cache.edit(diskKey(key)) ?: return@withContext
            try {
                editor.newSink(0).buffer().use { it.writeUtf8(text) }
                editor.commit()
            } catch (t: Throwable) {
                editor.abortSuppressing(t)
                throw t
            }
        }
    }

    private fun diskKey(key: String): String = key.encodeUtf8().sha256().hex()

    private companion object {
        const val LOADED_LIMIT = 2048
    }
}

internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
