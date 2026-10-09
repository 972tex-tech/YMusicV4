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
 * Where an updatable JS bundle (engine.js, player.js) comes from: the build's EMBEDDED copy is
 * the floor everything degrades to, a CACHED remote copy (persisted by [fetchIfAny]) rides above
 * it, and remote config names newer versions. Callers boot `getCached() ?: getEmbed()` and run
 * [fetchIfAny] periodically. A remote copy is only ever used once its signature checks out
 * ([verifyBundleSignature]) — on download AND on every cached load.
 */
interface JsSourceProvider {
    /** The cached remote bundle `(source, version)` — local reads only, never fetches. Null when
     *  nothing is cached, the cache is unreadable or fails its signature (it is then deleted), or
     *  the cached version is older than the embedded copy (an app update supersedes a stale push). */
    suspend fun getCached(): Pair<String, Long>?

    /** The build's embedded bundle `(source, version)` — always available, the fallback. */
    suspend fun getEmbed(): Pair<String, Long>

    /** Epoch millis of the last remote-config check (0 = never) — callers throttle on this. */
    suspend fun lastFetch(): Long

    /**
     * Check remote config and, when it names a version newer than [currentVersion], download that
     * bundle, verify its signature (a mismatch throws [BundleSignatureException]), run [consume]
     * to validate it (a throw rejects it — nothing is cached), then persist it for [getCached].
     * Returns [consume]'s result, or null when there was nothing newer (no remote config or no
     * pinned key included). Stamps [lastFetch] on every check.
     */
    suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (source: String, version: Long) -> T): T?
}

/** A published remote bundle, as remote config names it. [signature]: see [verifyBundleSignature]. */
data class SignedBundleRef(val version: Long, val url: String, val signature: String)

/** The remote-config seam: the latest published bundle, or null when none is published. */
fun interface JsConfigProvider {
    suspend fun fetchConfig(): SignedBundleRef?
}

/** A remote bundle whose bytes do not match its signature under the pinned key. */
class BundleSignatureException(message: String) : Exception(message)

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

/** The updatable MSE player bundle: the compiled-in `player.js` under a signed remote copy. */
fun playerJsSource(
    client: KmpHttpClient,
    config: JsConfigProvider,
    publicKey: String,
    fileSystem: FileSystem,
    directory: Path,
    now: () -> Long,
    logger: Logger? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
): JsSourceProvider = DefaultJsSourceProvider(
    client = client,
    config = config,
    name = PLAYER_BUNDLE,
    publicKey = publicKey,
    embedVersion = io.ymusic.dynamicengine.internal.PLAYER_JS_VERSION,
    embedSource = { io.ymusic.dynamicengine.internal.PLAYER_JS },
    fileSystem = fileSystem,
    directory = directory,
    now = now,
    logger = logger,
    ioDispatcher = ioDispatcher,
)

/** The signed [verifyBundleSignature] names of the two remote bundles. */
const val ENGINE_BUNDLE = "engine"
const val PLAYER_BUNDLE = "player"

/** An embed-only [JsSourceProvider] — no cache, no remote. Tests and fixed-bundle callers. */
class StaticJsSource(private val source: String, private val version: Long = 0L) : JsSourceProvider {
    override suspend fun getCached(): Pair<String, Long>? = null
    override suspend fun getEmbed(): Pair<String, Long> = source to version
    override suspend fun lastFetch(): Long = 0L
    override suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (String, Long) -> T): T? = null
}

/**
 * The file-backed [JsSourceProvider]: the cached bundle and its metadata live under [directory]
 * (`bundle.js` + `bundle.meta`), the embedded copy loads lazily through [embedSource]. A torn,
 * corrupt or tampered cache reads as absent, never an error: the embed is always there.
 *
 * The disk is not trusted — any process of this user can rewrite those files — so the cached
 * bundle is re-hashed and its stored signature re-checked on every [getCached].
 */
class DefaultJsSourceProvider(
    private val client: KmpHttpClient,
    private val config: JsConfigProvider,
    /** The bundle's signed name ([ENGINE_BUNDLE] / [PLAYER_BUNDLE]). */
    private val name: String,
    /** The pinned signing key, base64 SPKI. Blank: no remote bundle is ever trusted. */
    private val publicKey: String,
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

    private class Meta(val version: Long, val lastFetch: Long, val signature: String)

    private fun readMeta(): Meta? = try {
        if (!fileSystem.exists(metaFile)) {
            null
        } else {
            val lines = fileSystem.read(metaFile) { readUtf8() }.lines()
            val version = lines.getOrNull(0)?.toLongOrNull()
            val lastFetch = lines.getOrNull(1)?.toLongOrNull()
            if (version == null || lastFetch == null) null else Meta(version, lastFetch, lines.getOrNull(2).orEmpty())
        }
    } catch (e: okio.IOException) {
        log?.log(Logger.Level.WARN, "bundle meta unreadable — treating as no cache", e)
        null
    }

    private fun writeMeta(meta: Meta) {
        fileSystem.createDirectories(directory)
        fileSystem.write(metaFile) { writeUtf8("${meta.version}\n${meta.lastFetch}\n${meta.signature}\n") }
    }

    private fun authentic(version: Long, bytes: Buffer, signature: String): Boolean =
        publicKey.isNotBlank() && verifyBundleSignature(publicKey, name, version, bytes.sha256().hex(), signature)

    override suspend fun getCached(): Pair<String, Long>? = lock.withLock {
        withContext(ioDispatcher) {
            val meta = readMeta() ?: return@withContext null
            // An app update's embedded bundle supersedes an OLDER cached remote push.
            if (meta.version < embedVersion) return@withContext null
            val bytes = try {
                if (!fileSystem.exists(bundleFile)) return@withContext null
                fileSystem.read(bundleFile) { Buffer().also { readAll(it) } }
            } catch (e: okio.IOException) {
                log?.log(Logger.Level.WARN, "cached bundle unreadable — falling back to embed", e)
                return@withContext null
            }
            if (!authentic(meta.version, bytes, meta.signature)) {
                log?.log(Logger.Level.WARN, "cached $name bundle v${meta.version} fails its signature — deleted")
                fileSystem.delete(bundleFile, mustExist = false)
                writeMeta(Meta(-1L, meta.lastFetch, ""))
                return@withContext null
            }
            bytes.readUtf8() to meta.version
        }
    }

    override suspend fun getEmbed(): Pair<String, Long> = lock.withLock {
        embed ?: (embedSource() to embedVersion).also { embed = it }
    }

    override suspend fun lastFetch(): Long = lock.withLock {
        withContext(ioDispatcher) { readMeta()?.lastFetch ?: 0L }
    }

    override suspend fun <T> fetchIfAny(currentVersion: Long, consume: suspend (String, Long) -> T): T? {
        // A failed/absent config check still counts as a check (throttling).
        val remote = try {
            config.fetchConfig()
        } catch (e: Throwable) {
            runCatching { stampLastFetch() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        stampLastFetch()
        if (remote == null || publicKey.isBlank()) return null
        if (remote.version <= currentVersion) return null
        val bytes = download(remote.url)
        if (!authentic(remote.version, bytes, remote.signature)) {
            throw BundleSignatureException("$name bundle v${remote.version} from ${remote.url} fails its signature")
        }
        val source = bytes.snapshot().utf8()
        val result = consume(source, remote.version) // throw = rejected: nothing is cached
        lock.withLock {
            withContext(ioDispatcher) {
                fileSystem.createDirectories(directory)
                fileSystem.write(bundleFile) { writeAll(bytes) }
                writeMeta(Meta(remote.version, now(), remote.signature))
            }
        }
        return result
    }

    private suspend fun stampLastFetch() {
        lock.withLock {
            withContext(ioDispatcher) {
                val meta = readMeta()
                writeMeta(Meta(meta?.version ?: -1L, now(), meta?.signature.orEmpty()))
            }
        }
    }

    private suspend fun download(url: String): Buffer {
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
            return buffer
        } finally {
            try {
                response.close()
            } catch (_: Throwable) {
            }
        }
    }
}
