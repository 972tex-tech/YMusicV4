package io.ymusic.player.webview

import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.ProxyResponse
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import okio.Buffer
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * The desktop (JCEF) delivery adapter for the player cache: a `CefResourceHandler` that answers an
 * intercepted `/ymusicForward` request from [serve] ([LocalMediaProxy.serve] — the shared router) and
 * **streams** the body through. A media response is tens of MB, so it must never sit fully in memory:
 * once headers resolve, the process coroutine prebuffers ahead into [ready] — suspending on
 * [waitForDrain] while the buffer holds [READY_CAP] (a condition-variable-style park), woken by
 * [readResponse] draining it — so CEF reads come off the buffer while the network keeps running ahead of
 * them. Wire it in from the request handler's `getResourceHandler` for a URL containing `/ymusicForward`.
 */
internal class ProxyResourceHandler(
    private val serve: suspend (String) -> ProxyResponse,
    private val target: String,
    parentScope: CoroutineScope,
    private val log: kmpcommon.log.Logger? = null,
) : CefResourceHandlerAdapter() {

    // Header fields: written in [processRequest]'s coroutine before its Continue(), read by CEF in
    // [getResponseHeaders] after it — the Continue() hand-off publishes them.
    private var status = 200
    private var mimeType = "application/octet-stream"
    private var headers: List<Pair<String, String>> = emptyList()
    private var contentLength = -1L

    // Pulled-ahead, not-yet-served bytes, soft-capped at [READY_CAP] (overshoot by one chunk). All access
    // under [lock]: the prebuffer coroutine writes, a CEF thread reads. [waitForDrain] is the full-buffer
    // park: created by the prebuffer loop at the cap, completed by a readResponse drain / cancel().
    // [dataCallback] is CEF's parked read (buffer empty, more coming), Continued when the next chunk or EOF
    // lands. [failed] flags a mid-stream read error so it aborts rather than serving as a clean EOF.
    private val lock = Any()
    private val ready = Buffer()
    private var eof = false
    private var failed = false

    // A child of the parent's Job, so releasing the bridge (cancelling parentScope) also tears down this
    // handler's coroutine — `+ Job()` alone would detach it into a leaking root job.
    private val scope = parentScope + Job(parentScope.coroutineContext.job)
    private var waitForDrain: CompletableDeferred<Unit>? = null
    private var dataCallback: CefCallback? = null

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback ?: return false
        scope.launch {
            val response = try {
                serve(target)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                log?.log(kmpcommon.log.Logger.Level.WARN, "proxy serve failed: $target", error)
                status = 502
                synchronized(lock) { eof = true }
                callback.cancel()
                return@launch
            }
            response.body.use { body ->
                status = response.status
                headers = response.headers
                mimeType = response.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
                    ?.second?.substringBefore(';') ?: "application/octet-stream"
                contentLength = response.contentLength ?: -1L
                callback.Continue()
                prebuffer(body)
            }
        }
        return true
    }

    // Fills [ready] from the body until EOF / error / cancel: parks (suspends — never blocks a thread)
    // while the buffer holds [READY_CAP] or more; a drain in [readResponse] completes [waitForDrain] and
    // wakes it. Runs on the process coroutine; the body's `use` in [processRequest] releases upstream.
    private suspend fun prebuffer(src: AsyncSource) {
        while (true) {
            val wait = synchronized(lock) {
                if (eof) return
                if (ready.size >= READY_CAP) {
                    waitForDrain?.takeIf { !it.isCompleted } ?: CompletableDeferred<Unit>().also { waitForDrain = it }
                } else {
                    null
                }
            }
            if (wait != null) {
                wait.await()
                continue
            }
            val chunk = Buffer()
            val n = try {
                src.read(chunk, CHUNK)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // A mid-stream read failure must NOT masquerade as a clean EOF — that would serve the
                // truncated body as a complete response. Flag it and abort any parked read; readResponse
                // cancels the request once the already-buffered bytes drain.
                log?.log(kmpcommon.log.Logger.Level.WARN, "proxy body read failed: $target", t)
                synchronized(lock) {
                    failed = true
                    dataCallback?.cancel()
                    dataCallback = null
                }
                return
            }

            synchronized(lock) {
                if (n == -1L) eof = true else ready.writeAll(chunk)
                dataCallback?.Continue()
                dataCallback = null
                if (eof) return
            }
        }
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        response?.let { r ->
            r.status = status
            r.mimeType = mimeType
            headers.forEach { (k, v) -> r.setHeaderByName(k, v, true) }
        }
        // A known length lets CEF size the response; unknown (-1) streams until readResponse signals EOF.
        responseLength?.set(if (contentLength in 0..Int.MAX_VALUE.toLong()) contentLength.toInt() else -1)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        if (dataOut == null) {
            bytesRead?.set(0); return false
        }
        val drained = synchronized(lock) {
            when {
                ready.size > 0L -> {
                    // Buffer.read(ByteArray…) copies from the head segment only (≤8KB) and returns the
                    // count — loop to honor the full drain.
                    val n = minOf(bytesToRead.toLong(), ready.size).toInt()
                    var copied = 0
                    while (copied < n) copied += ready.read(dataOut, copied, n - copied)
                    bytesRead?.set(copied)
                    waitForDrain?.also { waitForDrain = null }
                }

                failed -> {
                    // Mid-stream error, nothing left buffered: abort the request rather than end it cleanly
                    // (which the requester's fetch would take as a complete, silently-truncated body).
                    callback?.cancel()
                    bytesRead?.set(0)
                    return true
                }

                eof -> {
                    bytesRead?.set(0)
                    return false // fully streamed
                }

                else -> {
                    // Buffer empty, more coming: park CEF's read — the prebuffer loop Continues it when the
                    // next chunk (or EOF) lands. CEF keeps one readResponse outstanding, one slot.
                    dataCallback = callback
                    bytesRead?.set(0)
                    return true
                }
            }
        }
        drained?.complete(Unit) // wake the prebuffer loop parked on a full buffer
        return true
    }

    override fun cancel() {
        // The WebView aborted the request (seek / stop / navigate) — cancel the coroutine (releasing the
        // upstream response via the body's `use`), then wake both parks under the lock so nothing stays
        // suspended on this handler.
        scope.cancel()
        synchronized(lock) {
            eof = true
            waitForDrain?.cancel()
            dataCallback?.cancel()
            waitForDrain = null
            dataCallback = null
        }
    }

    private companion object {
        const val CHUNK = 8L * 1024 // ~1 okio segment, so Buffer.read drains a chunk in one copy
        const val READY_CAP = 128L * 1024 // 16 chunks of read-ahead — keep the pipe full off-thread
    }
}
