package ymusic.dynamicengine

import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import ymusic.dynamicengine.internal.ENGINE_JS
import ymusic.dynamicengine.internal.installCache
import ymusic.dynamicengine.internal.installCookies
import ymusic.dynamicengine.internal.installHttp
import ymusic.quickjs.Pipe
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.QuickJsException
import ymusic.quickjs.QuickJsRuntime

/**
 * A [QuickJsRuntime] pre-wired with the host capabilities a downloadable scraping engine needs. The
 * point is that the scraping logic ships as JS that can be updated remotely — no app release — so
 * YouTube feed-shape changes don't surface to the user as errors.
 *
 * Today it wires **HTTP** — a global `HttpRequest` builder served directly on a [KmpHttpClient]
 * (streaming responses; on Android the jvm variant of `:kmpHttp:core` resolves, which is OkHttp) —
 * and **cookies**: the bundle's `Cookies.get`/`.set` over the `"cookie/get"` / `"cookie/set"`
 * commands, served on the app-provided cookie store. The engine JS owns cookie POLICY (the legacy
 * `YTHttpClientCookieStoreAndHeadersManager` role — building each request's `Cookie` header,
 * consent/SOCS synthesis, storing `Set-Cookie`s), so [create]'s `client` should carry NO cookie
 * jar — a jar would inject cookies underneath the JS. Pass the platform store (the one the WebView
 * player shares) as `cookieJar` so scraping and playback present one identity.
 * When a [CacheStore] is supplied it also wires **cache** — the bundle's `Cache` over `cache/…`,
 * a streaming keyed blob cache (JS owns eviction) for the InnerTube session params. File / SQLite
 * protocols are planned (see `quickjs/HOST_PROTOCOLS.md`).
 *
 * Creation boots the bundle and then drives its `"init"` command as the health check — a pipe
 * round-trip proving the whole stack (open routing, handler dispatch, JSON encode, END) — which
 * reports [backendVersion], the bundle's self-declared revision, for swap logs and per-revision
 * error attribution.
 *
 * The pipe/command plumbing is **internal**: callers don't open pipes or register handlers, they
 * call a task method (`getHomeFeed()`, …) that drives the engine JS over pipes under the hood and
 * serves any host command the JS calls back on (via [commandHandler], using [logger]). Not
 * thread-safe — the underlying runtime confines JS to a single thread. Build one, use it, [close] it.
 */
class DynamicEngine private constructor(
    private val runtime: QuickJsRuntime,
    internal val logger: Logger?,
    /** The bundle's self-declared revision (its `BACKEND_VERSION`), from the `"init"` check. */
    val backendVersion: Int,
) {

    /** Evaluate [script] synchronously (load engine code / register `ymix.onAccept` handlers). */
    internal suspend fun load(script: String, filename: String = "<engine>"): String? = runtime.load(script, filename)

    /** Handle pipes the engine JS opens with the exact [command] name (wrap [handler] in
     *  [commandHandler] for the error contract). */
    internal fun onAccept(command: String, handler: suspend (Pipe) -> Unit) = runtime.onAccept(command, handler)

    /** Open a pipe to the engine JS addressed to [command] (its `ymix.onAccept` handler serves it). */
    internal suspend fun openPipe(command: String): Pipe = runtime.openPipe(command)

    /** Suspend until nothing is in flight in either direction (see [QuickJsRuntime.awaitIdle]). */
    internal suspend fun awaitIdle() = runtime.awaitIdle()

    /** Free the runtime and its engine thread. Idempotent-safe to call once when done. */
    suspend fun close() = runtime.close()

    companion object {
        /**
         * Create an engine running [engineJs] — the single :engine-js bundle (transport + protocol
         * layers + scraping logic) — wire the `"http"` handler onto [client] (before the boot
         * eval, so the bundle's top-level code can already call it), and health-check via
         * `"init"`.
         *
         * [appVersion] is the host app's version code. [engineJs] defaults to the build-time
         * baseline; the app passes a remotely-fetched bundle here, and falls back to the default
         * when that fails to load (a bad push must not brick the engine — a failure here is a
         * typed [EngineError], `E20020` load / `E20021` health check). [logger] receives
         * host-protocol birth-site failures (null = logging off).
         */
        suspend fun create(
            client: KmpHttpClient,
            appVersion: Int,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
        ): DynamicEngine = boot(logger) {
            QuickJsRuntime.create(engineJs, appVersion, onLog = logger?.let(::jsLog)) {
                it.installHttp(client, logger)
                it.installCookies(cookieJar, logger)
                if (cacheStore != null) it.installCache(cacheStore, logger)
            }
        }

        /** Create from [compile]d bytecode of a bundle — `versions.app` is only known here, so it
         *  can never be baked into the precompiled blob; C installs it before the bytecode runs. */
        suspend fun create(
            client: KmpHttpClient,
            appVersion: Int,
            engineBytecode: ByteArray,
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
        ): DynamicEngine = boot(logger) {
            QuickJsRuntime.create(engineBytecode, appVersion, onLog = logger?.let(::jsLog)) {
                it.installHttp(client, logger)
                it.installCookies(cookieJar, logger)
                if (cacheStore != null) it.installCache(cacheStore, logger)
            }
        }

        /** Compile a bundle to QuickJS bytecode for [create] — per-function source text is
         *  stripped, so the blob carries no copy of the script. Compile once (e.g. after fetching
         *  a remote bundle), persist, and create from the bytes on later launches. */
        suspend fun compile(engineJs: String): ByteArray = QuickJsRuntime.compile(engineJs)

        private suspend fun boot(logger: Logger?, createRuntime: suspend () -> QuickJsRuntime): DynamicEngine {
            val runtime = try {
                createRuntime()
            } catch (e: QuickJsException) {
                throw EngineError.Technical("E20020", "engine bundle failed to load: ${e.message}", null, null, null, e)
            }
            try {
                return DynamicEngine(runtime, logger, healthCheck(runtime))
            } catch (t: Throwable) {
                runCatching { runtime.close() }
                throw t
            }
        }

        private const val INIT_TIMEOUT_MS = 10_000L

        /** Drive the bundle's `"init"` command; returns its self-reported backendVersion.
         *  Refused / RESET / malformed / timed out → `E20021` (the RESET's own code wins). */
        private suspend fun healthCheck(runtime: QuickJsRuntime): Int {
            val body = try {
                // Dispatchers.Default: the deadline must be wall-clock — a caller on a virtual-time
                // test dispatcher would otherwise skip straight to the timeout.
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        val pipe = runtime.openPipe("init")
                        try {
                            val buf = Buffer()
                            while (pipe.source.receive(buf, 4096L) != -1L) { /* to EOF */ }
                            buf.readUtf8()
                        } finally {
                            pipe.sink.close()
                            pipe.source.close()
                        }
                    }
                } ?: throw EngineError.Technical("E20021", "engine init timed out", null, null, null, null)
            } catch (e: EngineError) {
                throw e
            } catch (e: PipeResetException) {
                throw decodeEngineError(e.reason, e) // the bundle classified its own failure — keep its code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw EngineError.Technical("E20021", "engine init failed: ${e.message}", null, null, null, e)
            }
            val head = try {
                initJson.decodeFromString<InitHead>(body)
            } catch (e: Exception) {
                throw EngineError.Technical(
                    "E20021",
                    "engine init returned a malformed head: ${body.take(200)}",
                    null, null, null, e,
                )
            }
            return head.backendVersion
        }

        private val initJson = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class InitHead(val backendVersion: Int)

        private val JS_LOG_CTX = listOf("engine-js")

        /** The bundle's console + the unhandled-rejection tracker onto the app [Logger] —
         *  developer breadcrumbs (never shown to the user, per the two-channel error contract). */
        private fun jsLog(logger: Logger): (Int, String) -> Unit = { level, message ->
            when (level) {
                QuickJsRuntime.LOG_DEBUG -> logger.log(Logger.Level.DEBUG, JS_LOG_CTX, message)
                QuickJsRuntime.LOG_INFO -> logger.log(Logger.Level.INFO, JS_LOG_CTX, message)
                QuickJsRuntime.LOG_WARN -> logger.log(Logger.Level.WARN, JS_LOG_CTX, message)
                QuickJsRuntime.LOG_REJECTION ->
                    logger.log(Logger.Level.ERROR, JS_LOG_CTX, "unhandled promise rejection: $message")
                else -> logger.log(Logger.Level.ERROR, JS_LOG_CTX, message)
            }
        }
    }
}
