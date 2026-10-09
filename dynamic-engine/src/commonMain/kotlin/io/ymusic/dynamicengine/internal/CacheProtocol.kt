package io.ymusic.dynamicengine.internal

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.WireError
import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.PipeSink
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.quickjs.asAsyncSink
import io.ymusic.quickjs.asAsyncSource
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmpcommon.io.readAll
import kmpcommon.log.Logger
import kmphttp.json.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import okio.use
import projectcommon.error.CodedError
import projectcommon.error.TechnicalException
import projectcommon.error.codedError
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.stringOrNull
import projectcommon.log.FileAttachment

/**
 * Serves the `cache/…` pipe commands (the host side of the bundle's Cache client) on a [CacheStore].
 * Every op names a **scope** (an independent partition). All one-shot (HOST_PROTOCOLS §0); values are
 * **streamed** (head then body on the one pipe, delimited by half-close), so a large JSON blob never
 * lands whole in Kotlin.
 * ```
 * cache/get     JS→K {"scope":…,"key":…}              K→JS {"hit":true,size,updatedAt,lastUsed,key,meta} + bytes  (or {"hit":false})
 * cache/put     JS→K {"scope":…,"key":…,"meta":{…},"idleTimeoutMs"?:N} + bytes   K→JS {size,updatedAt,lastUsed,key,meta}
 * cache/delete  JS→K {"scope":…,"key":…}              K→JS (END)
 * cache/list    JS→K {"scope":…}                      K→JS [{size,updatedAt,lastUsed,key,meta}, …]
 * ```
 * A put body silent for `idleTimeoutMs` (default [PUT_IDLE_TIMEOUT_MS]; 0 = never) fails `E20052`.
 * Failures RESET the typed [WireError] contract: `E20050` (missing/blank `scope`/`key` or a malformed
 * head) and `E20051` (the store threw), a [CodedError] from the store keeping its own code.
 */
/**
 * `log/capture` — JS reports a coded failure (PO reject) and optionally names a cache blob
 * to attach. Host [Logger.captureError]; a file-backed store becomes a [FileAttachment] (pathname).
 */
internal fun QuickJsRuntime.installCapture(store: CacheStore?, parked: ParkedLoggers) {
    onAccept("log/capture") { pipe ->
        serve(pipe, parked, "log/capture") { head, _ ->
            val code = head.stringOrNull("code")?.takeIf { it.isNotEmpty() } ?: "E20088"
            val message = head.stringOrNull("message") ?: "PO rejected"
            val scopeName = head.stringOrNull("scope")
            val key = head.stringOrNull("key")
            val file = if (store != null && !scopeName.isNullOrEmpty() && !key.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    store.get(scopeName, key)?.filePath?.let { path ->
                        FileAttachment(path, "bg-session.json")
                    }
                }
            } else {
                null
            }
            val prop = mutableMapOf<String, Any>(
                "cache.scope" to (scopeName ?: ""),
                "cache.key" to (key ?: ""),
            )
            if (file != null) prop["bg-session.json"] = file
            parked.root?.captureError(TechnicalException(code, message), message, prop)
        }
    }
}

internal fun QuickJsRuntime.installCache(store: CacheStore, parked: ParkedLoggers) {
    // Every store touch leaves the engine thread (the engine-thread rule, AGENTS.md): SQLite
    // behind a lock or slow disk on the engine thread would wedge every command at once.
    onAccept("cache/get") { pipe ->
        serve(pipe, parked, "cache/get") { head, _ ->
            val scope = head.requireString("scope")
            val key = head.requireString("key")
            val entry = withContext(Dispatchers.IO) { store.get(scope, key) }
            if (entry == null) {
                pipe.sink.writeJson(buildJsonObject { put("hit", false) })
            } else {
                pipe.sink.writeJson(metaJson(entry.meta, hit = true))
                BufferedAsyncSource(entry.open()).use { it.readAll(pipe.sink.asAsyncSink()) }
            }
        }
    }
    onAccept("cache/put") { pipe ->
        serve(pipe, parked, "cache/put") { head, body ->
            val scope = head.requireString("scope")
            val key = head.requireString("key")
            val custom = head.readCustom()
            val idleMs = head.longOrNull("idleTimeoutMs") ?: PUT_IDLE_TIMEOUT_MS
            val source = if (idleMs > 0) body.idleTimeout(idleMs) else body
            val meta = withContext(Dispatchers.IO) { store.put(scope, key, custom, source) }
            pipe.sink.writeJson(metaJson(meta, hit = null))
        }
    }
    onAccept("cache/delete") { pipe ->
        serve(pipe, parked, "cache/delete") { head, _ ->
            val scope = head.requireString("scope")
            val key = head.requireString("key")
            withContext(Dispatchers.IO) { store.delete(scope, key) }
        }
    }
    onAccept("cache/list") { pipe ->
        serve(pipe, parked, "cache/list") { head, _ ->
            val scope = head.requireString("scope")
            val listed = withContext(Dispatchers.IO) { store.list(scope) }
            pipe.sink.writeJson(buildJsonArray { for (m in listed) add(metaJson(m, hit = null)) })
        }
    }
}

/** Reads the self-framing JSON head, then runs [handle] with it plus the source positioned at the
 *  body (for `cache/put`). Same error contract as the other host protocols. */
private suspend fun serve(
    pipe: Pipe,
    parked: ParkedLoggers,
    tag: String,
    handle: suspend (JsonObject, AsyncSource) -> Unit,
) {
    var log = parked.root?.fork(tag)
    try {
        val src = BufferedAsyncSource(pipe.source.asAsyncSource())
        val head = try {
            JsonReader(src).readObject()
        } catch (_: PipeResetException) {
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log?.log(Logger.Level.WARN, "E20050 malformed cache head: ${e.message}", e)
            pipe.sink.close(reason(WireError(code = "E20050", message = "malformed cache head: ${e.message}")))
            return
        }
        log = protocolLogger(parked, head, tag, (head.propOf("scope").orEmpty() + head.propOf("key").orEmpty()).ifEmpty { null })
        try {
            handle(head, src)
            pipe.sink.close() // END
        } catch (e: CancellationException) {
            throw e
        } catch (_: BrokenPipeException) {
            // the engine hung up mid-call
        } catch (e: Throwable) {
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

private fun JsonObject.requireString(field: String): String =
    stringOrNull(field)?.takeIf { it.isNotEmpty() }
        ?: throw CacheRequestError("cache request missing '$field'")

/** The caller's opaque custom metadata (`meta`): a string→string map; non-string values dropped. */
private fun JsonObject.readCustom(): Map<String, String> =
    objectOrNull("meta")?.let { meta -> meta.keys.mapNotNull { k -> meta.stringOrNull(k)?.let { k to it } } }
        .orEmpty().toMap()

private fun metaJson(meta: CacheStore.Meta, hit: Boolean?): JsonObject = buildJsonObject {
    if (hit != null) put("hit", hit)
    put("key", meta.key)
    put("size", meta.size)
    put("updatedAt", meta.updatedAt)
    put("lastUsed", meta.lastUsed)
    putJsonObject("meta") { for ((k, v) in meta.custom) put(k, v) }
}

private suspend fun PipeSink.writeJson(element: JsonElement) {
    val buf = Buffer().writeUtf8(element.toString())
    send(buf, buf.size)
}

private const val PUT_IDLE_TIMEOUT_MS = 30_000L

private fun AsyncSource.idleTimeout(millis: Long): AsyncSource = object : AsyncSource {
    override suspend fun read(sink: Buffer, byteCount: Long): Long =
        withTimeoutOrNull(millis) { this@idleTimeout.read(sink, byteCount) }
            ?: throw CachePutIdleError("cache put body idle for ${millis}ms")

    override fun close() = this@idleTimeout.close()
}

private class CachePutIdleError(message: String) : Exception(message), CodedError {
    override val code = "E20052"
    override val userMessage: String? = null
}

private class CacheRequestError(message: String) : Exception(message), CodedError {
    override val code = "E20050"
    override val userMessage: String? = null
}

private fun classify(e: Throwable): WireError {
    val coded = e.codedError()
    val message = e.message ?: e.toString()
    return WireError(
        code = coded?.code ?: SITE_CODE.find(message)?.value ?: "E20051",
        message = message,
        name = e::class.simpleName,
        userMessage = coded?.userMessage,
        stack = e.stackTraceToString().take(STACK_CAP),
    )
}

private fun reason(wire: WireError): String = Json.encodeToString(wire)
