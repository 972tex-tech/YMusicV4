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

/** Reports OkHttp's network stages at DEBUG into the request's [HttpCallLog] tag (absent tag: silent). */
internal object CallTraceListener : EventListener() {
    val factory = EventListener.Factory { this }

    private fun trace(call: Call): HttpCallLog? = call.request().tag(HttpCallLog::class.java)

    override fun callStart(call: Call) {
        trace(call)?.log(Logger.Level.DEBUG, "call start (interceptors done)")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        trace(call)?.log(Logger.Level.DEBUG, "connecting")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace(call)?.log(Logger.Level.DEBUG, "tls done")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        trace(call)?.log(Logger.Level.DEBUG, "connection acquired (${connection.protocol()})")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        trace(call)?.log(Logger.Level.DEBUG, "request sent")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        trace(call)?.log(Logger.Level.DEBUG, "response headers ${response.code} (${response.protocol})")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        trace(call)?.log(Logger.Level.DEBUG, "connect failed: ${ioe.message}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        trace(call)?.log(Logger.Level.DEBUG, "failed: ${ioe.message}")
    }

    override fun canceled(call: Call) {
        trace(call)?.log(Logger.Level.DEBUG, "canceled")
    }
}
