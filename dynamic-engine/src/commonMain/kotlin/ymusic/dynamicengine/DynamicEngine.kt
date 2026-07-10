package ymusic.dynamicengine

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import ymusic.dynamicengine.internal.ENGINE_JS
import ymusic.dynamicengine.internal.installHttp
import ymusic.quickjs.Pipe
import ymusic.quickjs.QuickJsRuntime

/**
 * A [QuickJsRuntime] pre-wired with the host capabilities a downloadable scraping engine needs. The
 * point is that the scraping logic ships as JS that can be updated remotely — no app release — so
 * YouTube feed-shape changes don't surface to the user as errors.
 *
 * Today it wires **HTTP**: a global `HttpRequest` builder served directly on a [KmpHttpClient]
 * (streaming responses; on Android the jvm variant of `:kmpHttp:core` resolves, which is OkHttp).
 * Storage / file / SQLite protocols are planned as further host-served pipe commands (see
 * `quickjs/HOST_PROTOCOLS.md`).
 *
 * The engine JS drives work over pipes: [load] engine code that registers `ymix.onAccept(command,…)`
 * handlers, then [openPipe] a command to invoke it (or vice-versa). Not thread-safe — the underlying
 * runtime confines JS to a single thread. Build one, use it, [close] it. All work is `suspend`.
 */
class DynamicEngine private constructor(private val runtime: QuickJsRuntime) {

    /** Evaluate [script] synchronously (to load engine code / register `ymix.onAccept` handlers). */
    suspend fun load(script: String, filename: String = "<engine>"): String? = runtime.load(script, filename)

    /** Handle pipes the engine JS opens with the exact [command] name. */
    fun onAccept(command: String, handler: suspend (Pipe) -> Unit) = runtime.onAccept(command, handler)

    /** Open a pipe to the engine JS addressed to [command] (its `ymix.onAccept` handler serves it). */
    suspend fun openPipe(command: String): Pipe = runtime.openPipe(command)

    /** Free the runtime and its engine thread. Idempotent-safe to call once when done. */
    suspend fun close() = runtime.close()

    companion object {
        /**
         * Create an engine running [engineJs] — the single :engine-js bundle (bootstrap + protocol
         * layers + scraping logic), evaluated as the runtime's bootstrap — and wire the `"http"`
         * handler onto [client].
         *
         * [appVersion] is the host app's version code, visible to the bundle before it evaluates
         * (`__appVersion` → the frozen `versions` global) so the remotely-updated JS can branch for
         * backward compatibility. [engineJs] defaults to the build-time baseline; the app passes a
         * remotely-fetched bundle here, and falls back to the default when that fails to load (a bad
         * push must not brick the engine). [logger] receives host-protocol birth-site failures
         * (null = logging off).
         */
        suspend fun create(
            client: KmpHttpClient,
            appVersion: Int,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
        ): DynamicEngine {
            val runtime = QuickJsRuntime.create(bootstrap = "globalThis.__appVersion=$appVersion;$engineJs")
            runtime.installHttp(client, logger)
            return DynamicEngine(runtime)
        }
    }
}
