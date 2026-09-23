package io.ymusic.dynamicengine

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * Where an updatable JS bundle (engine-js, webview-js) comes from: the build's EMBEDDED copy is
 * the floor everything degrades to, a CACHED remote copy (persisted by [fetchIfAny]) rides above
 * it, and remote config names newer versions. Callers boot `getCached() ?: getEmbed()` and run
 * [fetchIfAny] periodically.
 */
interface JsSourceProvider {
    /** The cached remote bundle `(source, version)` — local reads only, returns fast, never
     *  fetches. Null when nothing is cached, the cache is unreadable, or the cached version is
     *  older than the embedded copy (an app update supersedes a stale remote push). */
    suspend fun getCached(): Pair<String, Long>?

    /** The build's embedded bundle `(source, version)` — always available, the fallback. */
    suspend fun getEmbed(): Pair<String, Long>

    /** Epoch millis of the last remote-config check (0 = never) — callers throttle on this. */
    suspend fun lastFetch(): Long

    /**
     * Check remote config and, when it names a version newer than [currentVersion], download that
     * bundle, run [consume] to validate it (a throw rejects it — nothing is cached), then persist
     * it for [getCached]. Returns [consume]'s result, or null when there was nothing newer
     * (no remote config configured included). Stamps [lastFetch] on every check.
     */
    suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (source: String, version: Long) -> T): T?
}

/** The remote-config seam: names the latest published bundle (download url + version), or null
 *  when no remote config exists — the current state everywhere; a config service comes later. */
fun interface JsConfigProvider {
    suspend fun fetchConfig(): Pair<String, Long>?
}

/**
 * The BUILD-EMBEDDED engine bundle as a source — for the iOS lab shell, desktop probes and test
 * rigs. The Android/desktop apps load the same bundle from app assets instead
 * ([DefaultJsSourceProvider]), never call this, and R8 strips the embedded constant with it.
 */
fun embeddedEngineJsSource(): JsSourceProvider =
    StaticJsSource(io.ymusic.dynamicengine.internal.ENGINE_JS, io.ymusic.dynamicengine.internal.ENGINE_JS_VERSION)

/** Embedded MSE player bundle (`player.js`) — [DynamicEnginePlayer] default. */
fun embeddedPlayerJsSource(): JsSourceProvider =
    StaticJsSource(io.ymusic.dynamicengine.internal.PLAYER_JS, io.ymusic.dynamicengine.internal.PLAYER_JS_VERSION)

/** An embed-only [JsSourceProvider] — no cache, no remote. Tests and fixed-bundle callers. */
class StaticJsSource(private val source: String, private val version: Long = 0L) : JsSourceProvider {
    override suspend fun getCached(): Pair<String, Long>? = null
    override suspend fun getEmbed(): Pair<String, Long> = source to version
    override suspend fun lastFetch(): Long = 0L
    override suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (String, Long) -> T): T? = null
}

/**
 * The file-backed [JsSourceProvider]: the cached bundle and its metadata live under [directory]
 * (`bundle.js` + `bundle.meta`), the embedded copy loads lazily through [embedSource] (an app
 * asset — the bundle is no longer a compiled-in constant). A torn/corrupt cache reads as absent,
 * never an error: the embed is always there.
 */
class DefaultJsSourceProvider(
    private val client: KmpHttpClient,
    private val config: JsConfigProvider,
    private val embedVersion: Long,
    private val embedSource: suspend () -> String,
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val now: () -> Long,
    private val logger: Logger? = null,
    /** Blocking file IO runs here — `Dispatchers.IO` on the JVM shells; not a common API
     *  (internal on native), so the default matches DiskCacheStore's. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : JsSourceProvider {

    private val log = logger?.fork("js-source")
    private val lock = Mutex()
    private var embed: Pair<String, Long>? = null

    private val bundleFile get() = directory / "bundle.js"
    private val metaFile get() = directory / "bundle.meta"

    private class Meta(val version: Long, val lastFetch: Long)

    private fun readMeta(): Meta? = try {
        if (!fileSystem.exists(metaFile)) {
            null
        } else {
            val lines = fileSystem.read(metaFile) { readUtf8() }.lines()
            val version = lines.getOrNull(0)?.toLongOrNull()
            val lastFetch = lines.getOrNull(1)?.toLongOrNull()
            if (version == null || lastFetch == null) null else Meta(version, lastFetch)
        }
    } catch (e: okio.IOException) {
        log?.log(Logger.Level.WARN, "bundle meta unreadable — treating as no cache", e)
        null
    }

    private fun writeMeta(meta: Meta) {
        fileSystem.createDirectories(directory)
        fileSystem.write(metaFile) { writeUtf8("${meta.version}\n${meta.lastFetch}\n") }
    }

    override suspend fun getCached(): Pair<String, Long>? = lock.withLock {
        withContext(ioDispatcher) {
            val meta = readMeta() ?: return@withContext null
            // An app update's embedded bundle supersedes an OLDER cached remote push.
            if (meta.version < embedVersion) return@withContext null
            try {
                if (!fileSystem.exists(bundleFile)) null else fileSystem.read(bundleFile) { readUtf8() } to meta.version
            } catch (e: okio.IOException) {
                log?.log(Logger.Level.WARN, "cached bundle unreadable — falling back to embed", e)
                null
            }
        }
    }

    override suspend fun getEmbed(): Pair<String, Long> = lock.withLock {
        embed ?: (embedSource() to embedVersion).also { embed = it }
    }

    override suspend fun lastFetch(): Long = lock.withLock {
        withContext(ioDispatcher) { readMeta()?.lastFetch ?: 0L }
    }

    override suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (String, Long) -> T): T? {
        val remote = try {
            config.fetchConfig()
        } finally {
            stampLastFetch() // a failed/absent config check still counts as a check (throttling)
        }
        val (url, version) = remote ?: return null
        if (version <= currentVersion) return null
        val source = download(url)
        val result = consume(source, version) // throw = rejected: nothing is cached
        lock.withLock {
            withContext(ioDispatcher) {
                fileSystem.createDirectories(directory)
                fileSystem.write(bundleFile) { writeUtf8(source) }
                writeMeta(Meta(version, now()))
            }
        }
        return result
    }

    private suspend fun stampLastFetch() {
        lock.withLock {
            withContext(ioDispatcher) {
                val meta = readMeta()
                writeMeta(Meta(meta?.version ?: -1L, now()))
            }
        }
    }

    private suspend fun download(url: String): String {
        val response = client.execute(
            RequestBuilder().url(url).build(),
            client.defaultRedirectOptions,
            null,
        )
        try {
            check(response.code == 200) { "bundle download answered HTTP ${response.code}" }
            val src = response.body.asyncSource()
            val buffer = Buffer()
            val tmp = Buffer()
            while (true) {
                val read = src.read(tmp, 64 * 1024L)
                if (read == -1L) break
                buffer.write(tmp, read)
            }
            return buffer.readUtf8()
        } finally {
            try {
                response.close()
            } catch (_: Throwable) {
            }
        }
    }
}
