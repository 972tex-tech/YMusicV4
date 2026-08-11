package io.ymusic.player.webview

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceDate
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebKit interception experiment (macOS host; WebKit is the same engine on iOS/macOS). Proves a WKWebView
 * serves **Kotlin-synthesized bytes** for a request the page issues to a **custom scheme** — the mechanism
 * the player cache needs on iOS, where WKWebView cannot intercept https (WebKit #138169), so webview-js
 * must rewrite the media URL first (here: to `ymusic://…`).
 *
 * Register a [WKURLSchemeHandlerProtocol] for scheme `ymusic`; realize the webview in an off-screen
 * [NSWindow] under a headless [NSApplication] (WebKit won't run page JS while the view is detached); load a
 * page at the realistic `https://www.youtube.com/embed` origin that `fetch()`es `ymusic://media/hello`; the
 * handler returns bytes from Kotlin; the page posts the text back over the `WKScriptMessageHandler` bridge.
 *
 * Runs on macosX64 locally and macosArm64 on the mini. (A K/N iOS-simulator *unit test* can't host this —
 * it has no `UIApplication`, so `UIWindow` realization traps; the same code belongs in the iOS player-lab
 * app for an on-device run.)
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class WkSchemeInterceptExperimentTest {

    private val served = "hello from WKURLSchemeHandler".encodeToByteArray()

    /**
     * The mechanism works: a WKWebView serves our Kotlin bytes for a **same-origin** custom-scheme fetch
     * (page origin `ymusic://` → `fetch('ymusic://media/hello')`). This is the interception primitive the
     * iOS player cache would use — webview-js rewrites the media URL to `ymusic://`, the scheme handler
     * returns synthesized/cached bytes.
     */
    @Test
    fun wkwebview_serves_kotlin_bytes_for_a_same_origin_custom_scheme_fetch() {
        val message = fetchCustomScheme(pageOrigin = "ymusic://media/index.html")
        assertEquals("OK:hello from WKURLSchemeHandler", message, "same-origin custom-scheme fetch must hit our handler")
    }

    /**
     * The constraint: the SAME fetch from an **https** page origin (the realistic embed origin) is blocked
     * by WebKit before it reaches the handler — `TypeError: Load failed`, and the scheme handler is never
     * invoked. So a custom-scheme rewrite alone can't intercept media on the real embed page; the embed
     * must itself be served from the custom scheme (same-origin), or a loopback proxy must be used.
     */
    @Test
    fun cross_origin_https_to_custom_scheme_fetch_is_blocked() {
        val message = fetchCustomScheme(pageOrigin = "https://www.youtube.com/embed")
        assertTrue(
            message.startsWith("ERR:"),
            "cross-origin https→custom-scheme fetch should fail (was: $message)",
        )
    }

    /** Load a page at [pageOrigin] that fetches `ymusic://media/hello`; return the JS-reported result. */
    private fun fetchCustomScheme(pageOrigin: String): String {
        NSApplication.sharedApplication() // an app instance must exist for WebKit's window/JS to run
        val result = CompletableDeferred<String>()

        val config = WKWebViewConfiguration()
        config.setURLSchemeHandler(SchemeHandler(served) { note -> println("[wk] scheme: $note") }, forURLScheme = "ymusic")
        config.userContentController.addScriptMessageHandler(
            scriptMessageHandler = MessageHandler { body -> if (!result.isCompleted) result.complete(body) },
            name = "exp",
        )

        val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 320.0, 180.0), configuration = config)
        val navDelegate = NavDelegate { note -> println("[wk] nav: $note") }
        webView.navigationDelegate = navDelegate

        // Realize off-screen: WebKit won't run a detached view's JS. Borderless window far off-screen.
        val window = NSWindow(
            contentRect = CGRectMake(-10_000.0, -10_000.0, 320.0, 180.0),
            styleMask = NSWindowStyleMaskBorderless,
            backing = NSBackingStoreBuffered,
            defer = false,
        )
        window.contentView = webView
        window.makeKeyAndOrderFront(null)

        val html = """
            <html><head><script>
              fetch('ymusic://media/hello')
                .then(function(r){ return r.text(); })
                .then(function(t){ window.webkit.messageHandlers.exp.postMessage('OK:' + t); })
                .catch(function(e){ window.webkit.messageHandlers.exp.postMessage('ERR:' + e); });
            </script></head><body>x</body></html>
        """.trimIndent()
        webView.loadHTMLString(html, baseURL = NSURL(string = pageOrigin))

        val start = NSDate()
        while (!result.isCompleted) {
            NSRunLoop.mainRunLoop().runMode(NSDefaultRunLoopMode, beforeDate = NSDate().dateByAddingTimeInterval(0.02))
            if (NSDate().timeIntervalSinceDate(start) > 20.0) break
        }

        val message = if (result.isCompleted) result.getCompleted() else "TIMEOUT (scheme handler / bridge never fired)"
        println("[wk] pageOrigin=$pageOrigin RESULT=$message")
        window.close()
        return message
    }

    private class SchemeHandler(
        private val body: ByteArray,
        private val log: (String) -> Unit,
    ) : NSObject(), WKURLSchemeHandlerProtocol {
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
            val url = startURLSchemeTask.request.URL
            log("start ${url?.absoluteString}")
            val response = NSHTTPURLResponse(
                uRL = url!!,
                statusCode = 200,
                HTTPVersion = "HTTP/1.1",
                headerFields = mapOf(
                    "Content-Type" to "text/plain; charset=utf-8",
                    "Content-Length" to body.size.toString(),
                    "Access-Control-Allow-Origin" to "*",
                ),
            )
            startURLSchemeTask.didReceiveResponse(response)
            startURLSchemeTask.didReceiveData(body.toNSData())
            startURLSchemeTask.didFinish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
            log("stop")
        }
    }

    private class MessageHandler(private val onBody: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            onBody(didReceiveScriptMessage.body as? String ?: didReceiveScriptMessage.body.toString())
        }
    }

    private class NavDelegate(private val log: (String) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            log("didFinish")
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            log("didFail ${withError.localizedDescription}")
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            log("didFailProvisional ${withError.localizedDescription}")
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
