package ymusic.dynamicengine

import app.cash.sqldelight.db.SqlDriver
import kmpcommon.io.AsyncSource
import kmpcommon.io.writeAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import ymusic.dynamicengine.db.CacheDatabase

/**
 * A [CacheStore] whose **metadata index lives in SQLite** (via SqlDelight) while the **value blobs stay
 * on the filesystem**. That split is the point: `list(scope)` / `init` is a single indexed `SELECT`
 * instead of a directory scan plus a metadata-file read per entry — fast even for a large scope — and
 * a 100 MB download still streams straight to a file, never through the database.
 *
 * The [driver]'s schema must already be created ([CacheDatabase.Schema]); the app builds the platform
 * driver (JDBC / Android / sqliter) and the blob [directory] and hands them over. Blob path is
 * `sha256(scope, key)` under [directory]; writes stage to a `.tmp` file and `atomicMove`.
 *
 * **No zombie blobs** (a blob file with no metadata row). Every failure path cleans up: a cancelled
 * [put] deletes its half-written tmp; a [put] whose row write fails deletes the just-moved blob; [delete]
 * removes the blob **before** the row, so an interruption never strands a blob past its row; and [get]
 * self-heals the reverse — a row whose blob is gone (a torn delete, or a hard crash in the tiny
 * move→upsert gap) is deleted and reported as a miss. The only residual is that crash gap orphaning an
 * insert's blob, which the deterministic path reclaims on the next put of the same key.
 *
 * okio + SQLite calls are blocking, so they run on [ioDispatcher] (`Dispatchers.IO` on the JVM; the
 * caller passes it since `IO` is not a common API). [now] supplies epoch-millis stamps (injectable for
 * tests). Blob writes ([put]/[delete]) serialize on a [Mutex] (shared `.tmp` path); the DB serializes
 * its own writes, and reads ([get]'s `touch` + `list`) need no app lock. Eviction is the JS's job.
 */
class DiskCacheStore(
    driver: SqlDriver,
    private val directory: Path,
    private val fileSystem: FileSystem,
    private val now: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CacheStore {

    private val queries = CacheDatabase(driver).cacheEntryQueries
    private val writeLock = Mutex()

    override suspend fun get(scope: String, key: String): CacheStore.Entry? = withContext(ioDispatcher) {
        val stored = queries.selectOne(scope, key, ::toMeta).executeAsOneOrNull() ?: return@withContext null
        if (!fileSystem.exists(blobPath(scope, key))) {
            // A row whose blob is gone (a crash between the two, or a torn delete). Self-heal to a
            // miss so a row never outlives its blob and a later read can't fail on a missing file.
            writeLock.withLock { queries.deleteOne(scope, key) }
            return@withContext null
        }
        val ts = now()
        queries.touch(ts, scope, key) // bump lastUsed for LRU
        val meta = stored.copy(lastUsed = ts)
        object : CacheStore.Entry {
            override val meta = meta
            override fun open(): AsyncSource = FileAsyncSource(fileSystem, blobPath(scope, key), ioDispatcher)
        }
    }

    override suspend fun put(
        scope: String,
        key: String,
        custom: Map<String, String>,
        body: AsyncSource,
    ): CacheStore.Meta = writeLock.withLock {
        withContext(ioDispatcher) {
            fileSystem.createDirectories(directory)
            val customJson = json.encodeToString(custom)
            val tmp = tmpPath(scope, key)
            val blob = blobPath(scope, key)

            // 1. Stream to a temp file. A failure here (e.g. the caller cancels mid-put) removes the
            //    tmp and leaves any existing entry untouched — nothing half-written enters the cache.
            val size = try {
                fileSystem.sink(tmp).buffer().use { sink -> sink.writeAll(body) }
            } catch (e: Throwable) {
                fileSystem.delete(tmp, mustExist = false)
                throw e
            }

            // 2. Reveal the blob, then 3. write the row LAST. If the row write fails the just-revealed
            //    blob would be an orphan (a file with no meta row) — delete it, so no zombie survives.
            //    (A hard crash in the tiny gap between the move and the upsert can still orphan an
            //    insert's blob; the deterministic path reclaims it on the next put of the same key.)
            fileSystem.atomicMove(tmp, blob)
            val ts = now()
            try {
                queries.upsert(scope, key, size, ts, ts, customJson)
            } catch (e: Throwable) {
                fileSystem.delete(blob, mustExist = false)
                throw e
            }
            CacheStore.Meta(key, size, updatedAt = ts, lastUsed = ts, custom = custom)
        }
    }

    override suspend fun delete(scope: String, key: String) = writeLock.withLock {
        withContext(ioDispatcher) {
            // Blob first, then row: if this is interrupted the blob never outlives its row (no zombie).
            fileSystem.delete(blobPath(scope, key), mustExist = false)
            queries.deleteOne(scope, key)
            Unit
        }
    }

    override suspend fun list(scope: String): List<CacheStore.Meta> = withContext(ioDispatcher) {
        queries.selectByScope(scope, ::toMeta).executeAsList()
    }

    // Column order = table order: scope, key, size, updatedAt, lastUsed, custom.
    private fun toMeta(scope: String, key: String, size: Long, updatedAt: Long, lastUsed: Long, custom: String) =
        CacheStore.Meta(key, size, updatedAt, lastUsed, decodeCustom(custom))

    private fun decodeCustom(text: String): Map<String, String> = try {
        json.decodeFromString(text)
    } catch (_: SerializationException) {
        emptyMap()
    }

    private fun blobPath(scope: String, key: String) = directory / "${stem(scope, key)}$BLOB_EXT"
    private fun tmpPath(scope: String, key: String) = directory / "${stem(scope, key)}$BLOB_EXT.tmp"

    // Length-prefix the scope so (scope, key) hashes injectively — no separator ambiguity.
    private fun stem(scope: String, key: String) = "${scope.length}:$scope:$key".encodeUtf8().sha256().hex()

    private companion object {
        const val BLOB_EXT = ".v"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** An [AsyncSource] over a file, opened lazily on first read; okio reads run on [ioDispatcher]. */
private class FileAsyncSource(
    private val fileSystem: FileSystem,
    private val path: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : AsyncSource {
    private var source: BufferedSource? = null

    override suspend fun read(sink: Buffer, byteCount: Long): Long = withContext(ioDispatcher) {
        val s = source ?: fileSystem.source(path).buffer().also { source = it }
        s.read(sink, byteCount)
    }

    override fun close() {
        source?.close()
        source = null
    }
}
