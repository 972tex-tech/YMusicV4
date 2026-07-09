package ymusic.dynamicengine.internal

import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.RequestBodyX.asOneshotRequestBody
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.asyncSource
import kmphttp.json.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Buffer
import okio.IOException
import ymusic.quickjs.BrokenPipeException
import ymusic.quickjs.Pipe
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.QuickJsRuntime
import ymusic.quickjs.asAsyncSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Serves the `"http"` pipe command (the host side of [HTTP_JS]) directly on a [KmpHttpClient].
 *
 * Wire shape (v3, HOST_PROTOCOLS §1): one **control pipe** per exchange plus, when the request has
 * a body, one **body pipe** — control never carries bulk data, so cancellation is observable at
 * every stage:
 * ```
 * control  JS→K: {"method":"POST","url":…,"headers":[["k","v"],…],"opts":{…},"bodyCmd":"http/body/8"}
 *                …then nothing except an optional [int32 -1] = cancel
 * control  K→JS: {"status":…,"message":…,"url":"<final URL>","headers":[…]} + raw body bytes to EOF
 * body     K opens `bodyCmd` lazily on the transport's FIRST pull; JS writes raw bytes, END = done
 * ```
 * Both heads are compact JSON (self-framing — [JsonReader] stops at the value's end); both bodies
 * are EOF-delimited raw bytes, streamed — neither side ever holds a whole body. The watcher arms
 * right after the head: a `-1` (or a RESET of the control pipe's request direction —
 * `HttpCall.cancel` mid-flight) cancels the exchange even while it is parked inside
 * [KmpHttpClient.execute] with the body never pulled.
 *
 * Expected failures RESET the control pipe with a stage-tagged reason — `invalid request: …`
 * (bad head or a URL/header kmpHttp rejects), `request failed: …` (transport: DNS/connect/TLS/
 * timeout), `response body failed: …` (the stream died after the head was already sent). Only
 * protocol violations and bugs propagate to the runtime's generic throw→RESET backstop.
 */
internal fun QuickJsRuntime.installHttp(client: KmpHttpClient) {
    val runtime = this
    onAccept("http") { pipe -> serveHttp(runtime, pipe, client) }
}

private suspend fun serveHttp(runtime: QuickJsRuntime, pipe: Pipe, client: KmpHttpClient) {
    // Every EXPECTED failure is handled in place with a stage-tagged RESET reason — the runtime's
    // throw→RESET backstop is reserved for bugs and protocol violations. The finally must never
    // touch the sink: closeWrite is first-close-wins, so a clean END there would preempt the
    // backstop's RESET and an error would reach JS as a complete-looking (truncated) response.
    var bodyPipe: Pipe? = null
    try {
        coroutineScope {
            val requestScope = this
            val src = BufferedAsyncSource(pipe.source.asAsyncSource())

            val prepared = try {
                val head = JsonReader(src).readObject()
                val method = head["method"]?.jsonPrimitive?.content ?: "GET"
                val url = head["url"]?.jsonPrimitive?.content ?: ""
                val headers = (head["headers"] as? JsonArray).orEmpty().map { entry ->
                    val pair = entry.jsonArray
                    pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
                }
                val opts = head["opts"] as? JsonObject
                val bodyCmd = head["bodyCmd"]?.jsonPrimitive?.content

                // The request body rides its own pipe, opened lazily on the transport's first pull
                // — a response that never needs the body (cache hit, early error) never transfers
                // it. Raw bytes to EOF; a JS-side failure RESETs the body pipe and the read throws
                // here. No bodyCmd = no body: a bodiless POST/PUT/PATCH is left for the transport
                // to reject.
                val requestBody = when {
                    bodyCmd != null -> object : AsyncSource {
                        private var eof = false

                        override suspend fun read(sink: Buffer, byteCount: Long): Long {
                            if (eof) return -1L
                            val body = bodyPipe ?: runtime.openPipe(bodyCmd).also { bodyPipe = it }
                            val r = body.source.receive(sink, byteCount)
                            if (r == -1L) {
                                eof = true
                                release()
                            }
                            return r
                        }

                        override fun close() = release()

                        // Callable synchronously off the engine thread (close), so the suspend
                        // pipe teardown hops into the request scope. Don't hold the pipe past its
                        // EOF — the response may stream long after. bodyPipe stays set: if the
                        // scope dies before the launched job runs, serveHttp's finally closes it
                        // again (pipe close is idempotent), so the pipe can't leak through that
                        // window.
                        private fun release() {
                            val body = bodyPipe ?: return
                            requestScope.launch {
                                body.source.close()
                                body.sink.close()
                                bodyPipe = null
                            }
                        }
                    }.asOneshotRequestBody(contentLength = contentLength(headers))
                    else -> null
                }
                PreparedRequest(
                    request = RequestBuilder()
                        .url(url)
                        .apply { for ((name, value) in headers) addHeader(name, value) }
                        .method(method, requestBody)
                        .build(),
                    redirects = opts?.get("followRedirects")?.jsonPrimitive?.booleanOrNull
                        ?.let { RedirectOptions(followRedirects = it, followSslRedirects = it) }
                        ?: client.defaultRedirectOptions,
                    timeout = opts?.get("timeoutMs")?.jsonPrimitive?.longOrNull?.milliseconds
                        ?: client.defaultExecuteTimeout,
                )
            } catch (_: PipeResetException) {
                return@coroutineScope // the client abandoned the exchange before it started
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Malformed head JSON, missing fields, or a URL/header kmpHttp rejects — all the
                // client's fault, none of them started an exchange.
                pipe.sink.close("invalid request: ${e.message}")
                return@coroutineScope
            }

            val exchange = launch {
                val response = try {
                    client.execute(prepared.request, prepared.redirects, prepared.timeout)
                } catch (e: IOException) {
                    // The ROUTINE failure (DNS/connect/TLS/timeout) — reported, never thrown.
                    pipe.sink.close("request failed: ${e.message}")
                    return@launch
                }
                try {
                    val tmp = Buffer()
                    tmp.writeUtf8(
                        buildJsonObject {
                            put("status", response.code)
                            put("message", response.message)
                            put("url", response.request.url.toString()) // final URL after redirects
                            putJsonArray("headers") {
                                for ((name, value) in response.headers) {
                                    addJsonArray {
                                        add(name)
                                        add(value)
                                    }
                                }
                            }
                        }.toString(),
                    )
                    pipe.sink.send(tmp, tmp.size)
                    check(tmp.size == 0L)
                    val bodySrc = response.body.asyncSource()
                    while (true) {
                        val r = bodySrc.read(tmp, 8192L)
                        if (r == -1L) {
                            pipe.sink.close() // EOF
                            break
                        }
                        if (r > 0L) pipe.sink.send(tmp, r)
                    }
                } catch (_: BrokenPipeException) {
                    // The client closed its read end (cancelled/torn down) — no one left to tell.
                } catch (e: IOException) {
                    // The head is already out, so RESET is the only channel left: a clean EOF here
                    // would read as a complete (silently truncated) body.
                    pipe.sink.close("response body failed: ${e.message}")
                } finally {
                    response.closeQuietly()
                }
            }
            // Cancel watcher, armed immediately: the control pipe carries nothing after the head,
            // so -1 (or a RESET — HttpCall.cancel mid-flight) is observable at EVERY stage, even a
            // transport parked before pulling the body. Clean EOF = the client closed after
            // consuming the response. Anything else on the control pipe is a protocol violation
            // that throws to the backstop.
            val watcher = launch {
                try {
                    while (src.request(1)) {
                        val n = src.readInt()
                        if (n == -1) {
                            exchange.cancel()
                        } else {
                            error("unexpected data on the control pipe ($n)")
                        }
                    }
                } catch (e: PipeResetException) {
                    exchange.cancel() // the second cancel signal, not a failure
                }
            }
            exchange.join()
            watcher.cancel()
        }
        pipe.sink.close() // clean END on every deliberate exit (no-op after an in-place RESET)
    } finally {
        bodyPipe?.let {
            it.source.close()
            it.sink.close()
        }
        pipe.source.close()
    }
}

private class PreparedRequest(val request: Request, val redirects: RedirectOptions, val timeout: Duration?)

private fun contentLength(headers: List<Pair<String, String>>): Long =
    headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second?.toLongOrNull() ?: -1L

/** Fire-and-forget close on the cleanup path (see CLAUDE.md error-handling rule). */
private fun Response.closeQuietly() {
    try {
        close()
    } catch (_: Throwable) {
        // Intentionally ignored: releasing the connection must not mask the real result.
    }
}
