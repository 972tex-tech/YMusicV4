package io.ymusic.player.webview

import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.io.AsyncSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Buffer
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerWithReplyProtocol
import platform.WebKit.WKUserContentController
import platform.darwin.NSObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Apple (WKWebView) delivery adapter for the player cache — the counterpart of desktop's
 * `CefResourceHandler` and Android's `shouldInterceptRequest`. WKWebView **cannot** intercept https
 * requests (no `shouldInterceptRequest` equivalent; `WKURLSchemeHandler` is custom-scheme only), so
 * instead of rewriting `/videoplayback` to a URL the native side catches, webview-js on `darwin` talks
 * to this over the JS↔native message channel: `WKScriptMessageHandlerWithReply` — `postMessage` returns
 * a Promise, we reply. Pull-driven and framed (verified in `spikes/ios-msgchannel-probe`):
 *
 *   - `{op:"open", url, method, body?}` → we start [LocalMediaProxy.serveRequest] and reply
 *     `{requestId, status, headers}` (JSON string).
 *   - `{op:"read", requestId}` → reply the next body chunk as a **base64 string**, or `""` at EOF.
 *     (A with-reply handler can't serialize binary — `Data`/`ArrayBuffer` fail — so chunks ride base64.)
 *   - `{op:"cancel", requestId}` → release the stream.
 *
 * webview-js builds `new Response(new ReadableStream({ pull }))` from those chunks. Callbacks arrive on
 * the main thread; the (blocking) cache read / network forward runs on [Dispatchers.Default] and the
 * reply hops back to main. All stream state is main-confined, so it needs no lock.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal class AppleProxyChannel(
    private val proxy: LocalMediaProxy,
    private val log: (String) -> Unit = {},
) : NSObject(), WKScriptMessageHandlerWithReplyProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val streams = mutableMapOf<String, AsyncSource>() // requestId → response body, main-confined
    private var nextId = 0

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
        replyHandler: (Any?, String?) -> Unit,
    ) {
        val raw = didReceiveScriptMessage.body as? String
        if (raw == null) {
            replyHandler(null, "expected a string message")
            return
        }
        scope.launch {
            try {
                replyHandler(handle(raw), null)
            } catch (t: Throwable) {
                log("proxy channel error: ${t.message}")
                replyHandler(null, t.message ?: "proxy error")
            }
        }
    }

    private suspend fun handle(raw: String): String {
        val msg = Json.parseToJsonElement(raw).jsonObject
        return when (msg["op"]?.jsonPrimitive?.contentOrNull) {
            "open" -> open(msg)
            "read" -> read(msg["requestId"]?.jsonPrimitive?.contentOrNull)
            "cancel" -> { msg["requestId"]?.jsonPrimitive?.contentOrNull?.let(::close); "" }
            else -> throw IllegalArgumentException("unknown op")
        }
    }

    private suspend fun open(msg: JsonObject): String {
        val url = msg["url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing url")
        val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val body = msg["body"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) }
        val response = withContext(Dispatchers.Default) { proxy.serveRequest(url, method, body) }
        val id = "r${++nextId}"
        streams[id] = response.body
        return buildJsonObject {
            put("requestId", id)
            put("status", response.status)
            putJsonArray("headers") {
                response.headers.forEach { (k, v) -> addJsonArray { add(k); add(v) } }
            }
        }.toString()
    }

    /** Next chunk as base64, or `""` at EOF (which also releases the stream). */
    private suspend fun read(id: String?): String {
        val body = id?.let { streams[it] } ?: return ""
        val buf = Buffer()
        val n = withContext(Dispatchers.Default) { body.read(buf, CHUNK) }
        if (n == -1L) {
            close(id)
            return ""
        }
        return Base64.encode(buf.readByteArray())
    }

    /** Closing the body releases the upstream network response (the tee / caching source forwards close). */
    private fun close(id: String) {
        streams.remove(id)?.let { runCatching { it.close() } }
    }

    fun release() {
        streams.keys.toList().forEach(::close)
        scope.cancel()
    }
}

// One base64 reply per JS pull; ~33% inflation, so keep chunks modest but not tiny.
private const val CHUNK = 64L * 1024
