package ymusic.dynamicengine

import kmpcommon.error.codedError
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ymusic.quickjs.BrokenPipeException
import ymusic.quickjs.Pipe
import ymusic.quickjs.PipeSink
import ymusic.quickjs.PipeSource

/**
 * The HOST half of the error contract — the Kotlin twin of the bundle's `commandHandler`
 * (`engine-js/src/protocol/command.ts`). A [CommandHandler] serves a command JS opened: it reads its
 * own head off `source` (format not assumed — JSON, a line, length-prefixed, or nothing), does the
 * work, and writes the result to `sink`. Throwing is the error channel.
 *
 * [commandHandler] wraps one into a pipe handler for [ymusic.quickjs.QuickJsRuntime.onAccept] that
 * ENDs on success and, on ANY throw, classifies it into the typed [WireError] contract, LOGS it at
 * this birth site (with full native fidelity — the real [Throwable] — per CLAUDE.md "Log at the
 * birth site"), and RESETs the pipe with the structured reason so the JS caller's `callHost`/
 * `hostError` rebuild a coded error. The transport's own throw→RESET is only a dumb backstop
 * (message, no code); this is where the `{code, …}` contract is minted on the host side.
 */
fun interface CommandHandler {
    suspend fun handle(source: PipeSource, sink: PipeSink)
}

/**
 * Wrap [handler] in the host error contract. [logCtx] is the scope trail this command's birth-site
 * logs are tagged with (the caller's trail plus this command's hop); [logger] null = logging off.
 * A caller that hung up ([BrokenPipeException]) is the one silent path — no one left to report to;
 * real cancellation propagates so the runtime can tear down.
 */
fun commandHandler(
    logger: Logger? = null,
    logCtx: List<String> = emptyList(),
    handler: CommandHandler,
): suspend (Pipe) -> Unit = handler@{ pipe ->
    try {
        handler.handle(pipe.source, pipe.sink)
        pipe.sink.close(null) // END: call complete
    } catch (e: CancellationException) {
        throw e
    } catch (_: BrokenPipeException) {
        // the caller hung up mid-call — nothing to report, nowhere to report it
    } catch (e: Throwable) {
        val wire = classify(e)
        // Birth-site log: the real Throwable (stack, cause) stays here where the fidelity is; only
        // the {code, message} value crosses to the caller. A breadcrumb/warning, not a crash record.
        logger?.log(Logger.Level.WARN, logCtx, "${wire.code} ${wire.message}", e)
        // The RESET is first-close-wins; a failure of the close itself has nowhere to go — swallow.
        runCatching { pipe.sink.close(Json.encodeToString(wire)) }
    } finally {
        pipe.source.close()
    }
}

private val SITE_CODE = Regex("""E\d{5}""")
private const val STACK_CAP = 4096

/**
 * Classify a thrown value into the wire contract — the Kotlin twin of the bundle's `classify()`.
 * A [kmpcommon.error.CodedError] anywhere in the cause chain wins (wrap, never re-mint — the deepest
 * code is the most specific); else a site code embedded in the message is preserved (host reasons
 * can cross as text); else `E20002`, an uncaught host command-handler error. Domain protocols that
 * want a category (`network`, `parse`) throw a coded error carrying it, or classify in place the way
 * `HttpProtocol` does per stage.
 */
private fun classify(e: Throwable): WireError {
    val coded = e.codedError()
    val message = e.message ?: e.toString()
    val code = coded?.code
        ?: SITE_CODE.find(message)?.value
        ?: "E20002" // uncaught host command handler error
    return WireError(
        code = code,
        message = message,
        name = e::class.simpleName,
        userMessage = coded?.userMessage,
        stack = e.stackTraceToString().take(STACK_CAP),
    )
}
