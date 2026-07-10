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
        /** One of the frozen `versions` ints C installs before the bundle — the remotely-updated
         *  JS branches on them for backward compatibility (`versions.app >= 123`, `typeof __x ===
         *  "function"`); never a load handshake. Bump when the host-protocol surface changes
         *  (`versions.bootstrap`, the transport ABI, is owned by :quickjs's C). */
        private const val ENGINE_VERSION = 1

        /**
         * Create an engine running [engineJs] — the single :engine-js bundle (transport + protocol
         * layers + scraping logic), evaluated as the runtime's creation script — and wire the `"http"`
         * handler onto [client].
         *
         * [appVersion] is the host app's version code. [engineJs] defaults to the build-time
         * baseline; the app passes a remotely-fetched bundle here, and falls back to the default
         * when that fails to load (a bad push must not brick the engine). [logger] receives
         * host-protocol birth-site failures (null = logging off).
         */
        suspend fun create(
            client: KmpHttpClient,
            appVersion: Int,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
        ): DynamicEngine {
            val runtime = QuickJsRuntime.create(engineJs, ENGINE_VERSION, appVersion)
            runtime.installHttp(client, logger)
            return DynamicEngine(runtime)
        }

        /** Create from [compile]d bytecode of a bundle — `versions.app` is only known here, so it
         *  can never be baked into the precompiled blob; C installs it before the bytecode runs. */
        suspend fun create(
            client: KmpHttpClient,
            appVersion: Int,
            engineBytecode: ByteArray,
            logger: Logger? = null,
        ): DynamicEngine {
            val runtime = QuickJsRuntime.create(engineBytecode, ENGINE_VERSION, appVersion)
            runtime.installHttp(client, logger)
            return DynamicEngine(runtime)
        }

        /** Compile a bundle to QuickJS bytecode for [create] — per-function source text is
         *  stripped, so the blob carries no copy of the script. Compile once (e.g. after fetching
         *  a remote bundle), persist, and create from the bytes on later launches. */
        suspend fun compile(engineJs: String): ByteArray = QuickJsRuntime.compile(engineJs)
    }
}
