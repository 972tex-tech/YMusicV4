package ymusic.ytmedia.proxy

/**
 * The platform transport that binds a **loopback** socket and drives [LocalMediaProxy]. The WebView's
 * rewritten media requests hit [origin]. Concrete implementations are per platform: `SocketMediaProxyServer`
 * (plain http, desktop/Android) and `TlsMediaProxyServer` (https, iOS — WKWebView blocks an http subresource
 * fetch from the https embed as mixed content, so the iOS proxy serves TLS with a self-signed 127.0.0.1 cert
 * the WebView is told to trust; see `docs/player/youtube-playback.md`). An interface, not `expect`, so the
 * unused native/linux targets need no implementation.
 */
interface MediaProxyServer {
    /** Bind + start accepting on an ephemeral loopback port. [origin] is valid once this returns. */
    fun start()

    /** `http(s)://127.0.0.1:<port>` — the base the WebView's `/ymusicForward` rewrite points at. */
    val origin: String

    fun stop()
}
