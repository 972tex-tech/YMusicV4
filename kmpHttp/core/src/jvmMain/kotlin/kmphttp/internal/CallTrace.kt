package kmphttp.internal

import kmpcommon.log.Logger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * One request's timeline, from `execute()` to the response headers: each stage is a DEBUG line
 * `http#<id> <stage> +<ms>` on the client's logger, so a request that hangs shows how far it got —
 * still waiting for a thread, in the app's interceptors, connecting, sent, or waiting for the
 * server. Only method, host and path are logged (no query, headers, cookies or bodies).
 */
internal class CallTrace(private val logger: Logger, private val label: String) {
    private val id = nextId.incrementAndGet()
    private val startNanos = System.nanoTime()

    fun log(stage: String, error: Throwable? = null) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        logger.log(Logger.Level.DEBUG, "http#$id $stage +${ms}ms $label", error)
    }

    companion object {
        private val nextId = AtomicLong()

        fun label(request: Request): String = "${request.method} ${request.url.host}${request.url.encodedPath}"
    }
}

/** Reports OkHttp's network stages into the request's [CallTrace] tag (absent tag: silent). */
internal object CallTraceListener : EventListener() {
    val factory = EventListener.Factory { this }

    private fun trace(call: Call): CallTrace? = call.request().tag(CallTrace::class.java)

    override fun callStart(call: Call) {
        trace(call)?.log("call start (interceptors done)")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        trace(call)?.log("connecting")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace(call)?.log("tls done")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        trace(call)?.log("connection acquired (${connection.protocol()})")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        trace(call)?.log("request sent")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        trace(call)?.log("response headers ${response.code} (${response.protocol})")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        trace(call)?.log("connect failed: ${ioe.message}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        trace(call)?.log("failed: ${ioe.message}")
    }

    override fun canceled(call: Call) {
        trace(call)?.log("canceled")
    }
}
