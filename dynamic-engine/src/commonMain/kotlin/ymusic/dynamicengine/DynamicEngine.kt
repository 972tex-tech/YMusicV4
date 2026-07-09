package ymusic.dynamicengine

import kmphttp.KmpHttpClient
import kotlinx.serialization.json.JsonObject
import okio.Buffer
import ymusic.dynamicengine.internal.HTTP_JS
import ymusic.dynamicengine.internal.SERVE_JS
import ymusic.dynamicengine.internal.installHttp
import ymusic.quickjs.BrokenPipeException
import ymusic.quickjs.Pipe
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.PipeSource
import ymusic.quickjs.QuickJsException
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

    /**
     * Invoke a `serve(command, …)` handler in the engine JS: send [args] as the JSON head, hand
     * [read] the response stream, and rebuild any failure as a typed [EngineError] (HOST_PROTOCOLS
     * "Errors across the boundary") — the JS dispatcher classifies every throw into
     * `{code, message, …}` and RESETs the pipe with it.
     */
    suspend fun <T> call(command: String, args: JsonObject, read: suspend (PipeSource) -> T): T {
        val pipe = try {
            runtime.openPipe(command)
        } catch (e: QuickJsException) {
            // E20002: the engine JS never registered this command — a version mismatch or load failure
            throw EngineError.Technical("E20002", "E20002 no engine handler: ${e.message}", null, null, null, e)
        }
        try {
            val head = Buffer().writeUtf8(args.toString())
            pipe.sink.send(head, head.size)
            return read(pipe.source)
        } catch (e: PipeResetException) {
            throw decodeEngineError(e.reason, e)
        } catch (e: BrokenPipeException) {
            // The handler died before consuming the args (EPIPE won the race against its RESET);
            // the classified reason, if any, still waits on the source — serve() always closes its
            // sink (END or RESET) before abandoning its read side, so this drain terminates.
            throw try {
                val sink = Buffer()
                while (pipe.source.receive(sink, 8192L) != -1L) sink.clear()
                e
            } catch (r: PipeResetException) {
                decodeEngineError(r.reason, e)
            }
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    /** Free the runtime and its engine thread. Idempotent-safe to call once when done. */
    suspend fun close() = runtime.close()

    companion object {
        /** Create an engine, wire the `"http"` handler onto [client], and install `HttpRequest`. */
        suspend fun create(client: KmpHttpClient): DynamicEngine {
            val runtime = QuickJsRuntime.create()
            runtime.installHttp(client)
            runtime.load(SERVE_JS, "<serve>")
            runtime.load(HTTP_JS, "<http>")
            return DynamicEngine(runtime)
        }
    }
}
