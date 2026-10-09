package io.ymusic.dynamicengine

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.dynamicengine.db.CacheDatabase
import kmpcommon.io.AsyncSource
import kmpcommon.io.writeAll
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
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
import projectcommon.error.CodedError
import kotlin.random.Random

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
 * tests). Eviction is the JS's job.
 *
 * **Concurrent writers: the last to START wins.** A put streams into its own tmp with no lock held (a
 * body can stay open for a whole player session). A [put] or [delete] of a key cancels that key's
 * in-flight put, which fails `E20053` and drops its tmp; a put already done streaming but overtaken
 * before its reveal fails the same way. [writeLock] guards only the per-key bookkeeping and the short
 * reveal (move + row) / delete; reads ([get]'s `touch` + `list`) take no app lock.
 */
class DiskCacheStore(
    driver: SqlDriver,
    private val directory: Path,
    private val fileSystem: FileSystem,
    private val now: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    logger: Logger? = null,
) : CacheStore {

    private val log = logger?.fork("engine-cache")

    private val queries = CacheDatabase(driver).cacheEntryQueries
    private val writeLock = Mutex()

    /** Per blob stem; guarded by [writeLock]. Dropped once no put of the key is in flight. */
    private class KeyTurn(var latest: Long = 0, var inFlight: Int = 0, var streaming: Job? = null)
    private val turns = HashMap<String, KeyTurn>()
    private var lastTicket = 0L

    override suspend fun get(scope: String, key: String): CacheStore.Entry? = withContext(ioDispatcher) {
        val stored = queries.selectOne(scope, key, ::toMeta).executeAsOneOrNull() ?: return@withContext null
        val blob = blobPath(scope, key)
        if (!fileSystem.exists(blob)) {
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
            override val filePath = blob.toString()
            override fun open(): AsyncSource = FileAsyncSource(fileSystem, blob, ioDispatcher)
        }
    }

    override suspend fun put(
        scope: String,
        key: String,
        custom: Map<String, String>,
        body: AsyncSource,
    ): CacheStore.Meta = coroutineScope {
        val id = stem(scope, key)
        val tmp = tmpPath(scope, key)
        val blob = blobPath(scope, key)
        val customJson = json.encodeToString(custom)
        val streaming = async(ioDispatcher, start = CoroutineStart.LAZY) {
            fileSystem.createDirectories(directory)
            fileSystem.sink(tmp).buffer().use { sink -> sink.writeAll(body) }
        }
        val ticket = writeLock.withLock {
            val turn = turns.getOrPut(id) { KeyTurn() }
            turn.inFlight++
            turn.streaming?.cancel(SUPERSEDED)
            turn.streaming = streaming
            (++lastTicket).also { turn.latest = it }
        }
        try {
            log?.log(Logger.Level.INFO, "DBG put stage $scope/$key #$ticket")
            val size = try {
                streaming.await()
            } catch (e: CancellationException) {
                if (streaming.isCancelled && isActive) throw CachePutSupersededError(scope, key, e) else throw e
            }
            log?.log(Logger.Level.INFO, "DBG put staged $scope/$key #$ticket wait")
            writeLock.withLock {
                val superseded = turns.getValue(id).latest != ticket
                log?.log(Logger.Level.INFO, "DBG put LOCKED $scope/$key #$ticket superseded=$superseded")
                if (superseded) throw CachePutSupersededError(scope, key)
                withContext(ioDispatcher) {
                    // Reveal the blob, then write the row LAST; a failed row write deletes the
                    // just-revealed blob so no zombie survives.
                    fileSystem.atomicMove(tmp, blob)
                    val ts = now()
                    try {
                        queries.upsert(scope, key, size, ts, ts, customJson)
                    } catch (e: Throwable) {
                        runCatching { fileSystem.delete(blob, mustExist = false) }.exceptionOrNull()?.let(e::addSuppressed)
                        throw e
                    }
                    CacheStore.Meta(key, size, updatedAt = ts, lastUsed = ts, custom = custom)
                }
            }
        } catch (e: Throwable) {
            runCatching { fileSystem.delete(tmp, mustExist = false) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        } finally {
            withContext(NonCancellable) {
                writeLock.withLock {
                    val turn = turns.getValue(id)
                    if (turn.streaming === streaming) turn.streaming = null
                    if (--turn.inFlight == 0) turns.remove(id)
                }
            }
        }
    }

    override suspend fun delete(scope: String, key: String) = writeLock.also { log?.log(Logger.Level.INFO, "DBG delete wait $scope/$key") }.withLock {
        log?.log(Logger.Level.INFO, "DBG delete LOCKED $scope/$key")
        turns[stem(scope, key)]?.let { turn ->
            turn.streaming?.cancel(SUPERSEDED)
            turn.latest = ++lastTicket
        }
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
    } catch (e: SerializationException) {
        emptyMap<String, String>().also { log?.log(Logger.Level.WARN, "corrupt cache meta dropped", e) }
    }

    private fun blobPath(scope: String, key: String) = directory / "${stem(scope, key)}$BLOB_EXT"
    private fun tmpPath(scope: String, key: String) =
        directory / "${stem(scope, key)}.${Random.nextLong().toULong().toString(16)}$BLOB_EXT.tmp"

    // Length-prefix the scope so (scope, key) hashes injectively — no separator ambiguity.
    private fun stem(scope: String, key: String) = "${scope.length}:$scope:$key".encodeUtf8().sha256().hex()

    private class CachePutSupersededError(scope: String, key: String, cause: Throwable? = null) :
        Exception("cache put $scope/$key superseded by a newer put/delete", cause),
        CodedError {
        override val code = "E20053"
        override val userMessage: String? = null
    }

    private companion object {
        val SUPERSEDED = CancellationException("superseded by a newer put/delete of the key")

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
