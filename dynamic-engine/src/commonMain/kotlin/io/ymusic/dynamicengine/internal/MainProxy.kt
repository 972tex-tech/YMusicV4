package io.ymusic.dynamicengine.internal

import io.ymusic.dynamicengine.WireError
import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.PipeSink
import io.ymusic.quickjs.QuickJsException
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.quickjs.asAsyncSource
import kmpcommon.io.AsyncSource
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import projectcommon.error.CodedError
import projectcommon.error.codedError

internal const val MAIN_PREFIX = "host/main/"

/**
 * Player-only prefix `"host/main/"`: JS `open("host/main/"+command)` is spliced onto that command
 * on the current main engine-js session. Args and result ride the pipe as-is. Not installed on main.
 */
internal fun QuickJsRuntime.installMainProxy(
    parked: ParkedLoggers,
    openOnMain: suspend (command: String) -> Pipe,
) {
    onAcceptPrefix(MAIN_PREFIX) { pipe -> serveMainProxy(pipe, parked, openOnMain) }
}

private suspend fun serveMainProxy(
    pipe: Pipe,
    parked: ParkedLoggers,
    openOnMain: suspend (command: String) -> Pipe,
) {
    val command = pipe.command.removePrefix(MAIN_PREFIX)
    val log = parked.root?.fork("main", mapOf("command" to command))
    var mainPipe: Pipe? = null
    try {
        if (command.isEmpty()) {
            log?.log(Logger.Level.WARN, "E20079 main-proxy missing command")
            pipe.sink.close(reason(WireError(code = "E20079", message = "main-proxy missing command")))
            return
        }
        withContext(Dispatchers.Default) {
            val opened = try {
                openOnMain(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuickJsException) {
                throw MainProxyError("E20080", e.message ?: "main JS refused $command", e)
            } catch (e: MainProxyError) {
                throw e
            } catch (e: IllegalStateException) {
                throw MainProxyError("E20078", e.message ?: "no main engine", e)
            }
            mainPipe = opened
            coroutineScope {
                launch { copyDir(pipe.source.asAsyncSource(), opened.sink) }
                launch { copyDir(opened.source.asAsyncSource(), pipe.sink) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: BrokenPipeException) {
        // peer hung up
    } catch (e: Throwable) {
        val wire = classifyMain(e)
        runCatching { pipe.sink.close(reason(wire)) }.exceptionOrNull()?.let(e::addSuppressed)
        log?.log(Logger.Level.WARN, "${wire.code} ${wire.message}", e)
    } finally {
        runCatching { pipe.source.close() }
        val mp = mainPipe
        if (mp != null) {
            runCatching { mp.source.close() }
            runCatching { mp.sink.close() }
        }
    }
}

private suspend fun copyDir(src: AsyncSource, dst: PipeSink) {
    val buf = Buffer()
    try {
        while (true) {
            val n = src.read(buf, COPY)
            if (n < 0L) {
                dst.close(null)
                return
            }
            dst.send(buf, n)
        }
    } catch (e: PipeResetException) {
        runCatching { dst.close(e.reason) }
    } catch (_: BrokenPipeException) {
        // peer closed its read
    } catch (e: CancellationException) {
        runCatching { dst.close("send aborted mid-stream: ${e.message}") }
        throw e
    }
}

private class MainProxyError(override val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause),
    CodedError {
    override val userMessage: String? = null
}

private fun classifyMain(e: Throwable): WireError {
    val coded = e.codedError()
    val message = e.message ?: e.toString()
    return WireError(
        code = coded?.code ?: "E20078",
        message = message,
        name = e::class.simpleName,
    )
}

private fun reason(wire: WireError): String = Json.encodeToString(wire)

private const val COPY = 64 * 1024L
