package io.ymusic.dynamicengine

import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeSink
import io.ymusic.quickjs.PipeSource
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import projectcommon.error.codedError

/**
 * The HOST half of the error contract — the Kotlin twin of the bundle's `commandHandler`
 * (`engine-js/src/protocol/command.ts`). A [CommandHandler] serves a command JS opened: it reads its
 * own head off `source` (format not assumed — JSON, a line, length-prefixed, or nothing), does the
 * work, and writes the result to `sink`. Throwing is the error channel.
 *
 * Internal plumbing: a [DynamicEngine] task method wraps one with [DynamicEngine.commandHandler] and
 * registers it on a host command; callers never see it (they call the task method).
 */
internal fun interface CommandHandler {
    suspend fun handle(source: PipeSource, sink: PipeSink)
}

/**
 * Wrap [handler] in the host error contract → a pipe handler for [DynamicEngine.onAccept]. ENDs on
 * success; on ANY throw, classifies it into the typed [WireError], LOGS it at this birth site
 * through [DynamicEngine.logger] (with full native fidelity — the real [Throwable] — per AGENTS.md
 * "Log at the birth site"), and RESETs the pipe with the structured reason so the JS caller's
 * `callHost`/`hostError` rebuild a coded error. The transport's own throw→RESET is only a dumb
 * backstop (message, no code); this is where the `{code, …}` contract is minted on the host side.
 *
 * [log] is the scope-trail logger these birth-site logs go through (the caller's trail plus this
 * command's hop; defaults to the engine's root logger). A caller that hung up
 * ([BrokenPipeException]) is the one silent path; real cancellation propagates so the runtime can
 * tear down.
 */
internal fun DynamicEngine.commandHandler(
    log: Logger? = logger,
    handler: CommandHandler,
): suspend (Pipe) -> Unit = { pipe ->
    try {
        handler.handle(pipe.source, pipe.sink)
        pipe.sink.close(null) // END: call complete
    } catch (e: CancellationException) {
        throw e
    } catch (_: BrokenPipeException) {
        // the caller hung up mid-call — nothing to report, nowhere to report it
    } catch (e: Throwable) {
        val wire = classify(e)
        // The RESET is first-close-wins; a failure of the close itself rides the log as suppressed.
        runCatching { pipe.sink.close(Json.encodeToString(wire)) }.exceptionOrNull()?.let(e::addSuppressed)
        // Birth-site log: the real Throwable (stack, cause) stays here where the fidelity is; only
        // the {code, message} value crosses to the caller. A breadcrumb/warning, not a crash record.
        log?.log(Logger.Level.WARN, "${wire.code} ${wire.message}", e)
    } finally {
        pipe.source.close()
    }
}

private val SITE_CODE = Regex("""E\d{5}""")
private const val STACK_CAP = 4096

/**
 * Classify a thrown value into the wire contract — the Kotlin twin of the bundle's `classify()`.
 * A [projectcommon.error.CodedError] anywhere in the cause chain wins (wrap, never re-mint — the deepest
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
