package io.ymusic.dynamicengine.internal

import io.ymusic.dynamicengine.WireError
import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.quickjs.asAsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kmphttp.CookieJar
import kmphttp.HttpUrl
import kmphttp.json.JsonReader
import kmphttp.loadForRequest
import kmphttp.parseCookie
import kmphttp.saveFromResponse
import kmphttp.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okio.Buffer
import projectcommon.error.CodedError
import projectcommon.error.codedError
import projectcommon.json.arrayOrNull
import projectcommon.json.stringOrNull

/**
 * Serves the `"cookie/get"` / `"cookie/set"` pipe commands (the host side of the bundle's Cookies
 * client) on the app-provided [cookieJar] — a file-backed persistent jar, not a WebView store.
 * The engine JS owns cookie **policy**: its
 * HTTP client carries no jar, the bundle reads this store, cooks each request's `Cookie` header
 * itself (consent/SOCS synthesis included), and writes `Set-Cookie`s back here — so the scraping
 * requests and the player present one identity, decided entirely by the remotely-updatable JS.
 * `CookieJar.NO_COOKIES` simply drops writes and reads back empty.
 *
 * Each command is **one-shot** (HOST_PROTOCOLS §0): one pipe = one call, the head is self-framing
 * JSON, no session state. `Set-Cookie` values are parsed with `kmphttp.parseCookie` (OkHttp's
 * grammar — domain/path/expiry), so the host, not the JS, owns cookie *parsing*.
 * ```
 * cookie/get  JS→K: {"url":…,"log":?}                    K→JS: {"<name>":"<value>",…}   (then EOF)
 * cookie/set  JS→K: {"url":…,"setCookie":[…],"log":?}     K→JS: (no body, clean END)
 * ```
 * Expected failures RESET with the typed [WireError] contract, classified HERE: `E20040` (missing/
 * blank/invalid `url`, missing `setCookie`, or a malformed head — a caller bug) and `E20041` (the
 * jar itself threw — a persistent jar's I/O failure), a [CodedError] from the jar keeping its own
 * code. The bundle's `hostError` adopts the code as-is.
 */
internal fun QuickJsRuntime.installCookies(cookieJar: CookieJar, parked: ParkedLoggers) {
    onAccept("cookie/get") { pipe ->
        serveCookie(pipe, parked, "cookie/get") { head, _ ->
            val url = head.requireHttpUrl("url")
            // The jar call leaves the engine thread (the engine-thread rule, AGENTS.md): the
            // Android jar is CookieManager, which can block against Chromium startup — on the
            // engine thread that wedged every pipe, timer and command at once.
            val cookies = withContext(Dispatchers.IO) { cookieJar.loadForRequest(url) }
            val out = Buffer().writeUtf8(
                buildJsonObject { for (c in cookies) put(c.name, c.value) }.toString(),
            )
            pipe.sink.send(out, out.size)
        }
    }
    onAccept("cookie/set") { pipe ->
        serveCookie(pipe, parked, "cookie/set") { head, log ->
            val url = head.requireHttpUrl("url")
            val raw = head.requireStringList("setCookie")
            // Cookie NAMES only — values are session credentials and must never enter a log line.
            log?.debug { "${url.host} set-cookie=${raw.joinToString(",") { it.substringBefore('=') }}" }
            // Unparseable Set-Cookie values are dropped (OkHttp semantics), not an error.
            val cookies = raw.mapNotNull { parseCookie(url, it) }
            withContext(Dispatchers.IO) { cookieJar.saveFromResponse(url, cookies) }
        }
    }
}

private suspend fun serveCookie(
    pipe: Pipe,
    parked: ParkedLoggers,
    tag: String,
    handle: suspend (JsonObject, Logger?) -> Unit,
) {
    var log = parked.root?.fork(tag) // caller trail + this command; this fallback until the head parses
    try {
        val src = BufferedAsyncSource(pipe.source.asAsyncSource())
        val head = try {
            JsonReader(src).readObject()
        } catch (_: PipeResetException) {
            return // the engine abandoned the call before it started
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log?.log(Logger.Level.WARN, "E20040 malformed cookie head: ${e.message}", e)
            pipe.sink.close(reason(WireError(code = "E20040", message = "malformed cookie head: ${e.message}")))
            return
        }
        log = protocolLogger(parked, head, tag, head.propOf("url"))
        try {
            handle(head, log)
            pipe.sink.close() // clean END (no-op after an in-place RESET)
        } catch (e: CancellationException) {
            throw e
        } catch (_: BrokenPipeException) {
            // the engine hung up mid-call — nothing left to report to
        } catch (e: Throwable) {
            // Birth-site log with full native fidelity; only the {code, message} value crosses.
            val wire = classify(e)
            runCatching { pipe.sink.close(reason(wire)) }.exceptionOrNull()?.let(e::addSuppressed)
            log?.log(Logger.Level.WARN, "${wire.code} ${wire.message}", e)
        }
    } finally {
        pipe.source.close()
    }
}

private val SITE_CODE = Regex("""E\d{5}""")
private const val STACK_CAP = 4096

/** A non-empty, well-formed URL field of the head; anything else is a caller bug (`E20040`). */
private fun JsonObject.requireHttpUrl(key: String): HttpUrl {
    val raw = stringOrNull(key)?.takeIf(String::isNotEmpty)
        ?: throw CookieRequestError("cookie request missing '$key'")
    return raw.toHttpUrlOrNull() ?: throw CookieRequestError("cookie request has an invalid '$key': $raw")
}

/** A non-empty array of non-empty strings; the `Cookies.set` client always sends `setCookie` as an
 *  array (a lone header is a one-element array). Absent/empty is a caller bug (`E20040`). */
private fun JsonObject.requireStringList(key: String): List<String> {
    val values = arrayOrNull(key)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) }
        .orEmpty()
    return values.ifEmpty { throw CookieRequestError("cookie request missing '$key'") }
}

private class CookieRequestError(message: String) : Exception(message), CodedError {
    override val code = "E20040"
    override val userMessage: String? = null
}

/** Classify a jar failure into the wire contract: a [CodedError] keeps its code (deepest wins),
 *  else a site code embedded in the message, else `E20041` (an unclassified cookie-jar failure). */
private fun classify(e: Throwable): WireError {
    val coded = e.codedError()
    val message = e.message ?: e.toString()
    return WireError(
        code = coded?.code ?: SITE_CODE.find(message)?.value ?: "E20041",
        message = message,
        name = e::class.simpleName,
        userMessage = coded?.userMessage,
        stack = e.stackTraceToString().take(STACK_CAP),
    )
}

private fun reason(wire: WireError): String = Json.encodeToString(wire)
