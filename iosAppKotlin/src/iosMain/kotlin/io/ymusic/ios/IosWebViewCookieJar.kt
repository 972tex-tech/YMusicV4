package io.ymusic.ios

import io.ymusic.ui.SessionCookie
import kmphttp.Cookie
import kmphttp.CookieBuilder
import kmphttp.CookieJar
import kmphttp.HttpUrl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieExpires
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

/** The shared persistent cookie store for InnerTube HTTP and the iOS WKWebView player. */
@OptIn(ExperimentalForeignApi::class)
internal class IosWebViewCookieJar(
    private val dataStore: WKWebsiteDataStore = WKWebsiteDataStore.defaultDataStore(),
) : CookieJar {
    // Keep a real WebView alive and start WebKit before the engine touches its cookie store. This
    // mirrors an earlier prototype; the player WebView uses this same default WKWebsiteDataStore.
    private val cookieHost = WKWebView(
        frame = CGRectMake(0.0, 0.0, 1.0, 1.0),
        configuration = WKWebViewConfiguration().apply { websiteDataStore = dataStore },
    ).apply {
        loadRequest(NSURLRequest(NSURL(string = "about:blank")))
    }
    private val store: WKHTTPCookieStore = cookieHost.configuration.websiteDataStore.httpCookieStore

    override suspend fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = withContext(Dispatchers.Main) {
        for (cookie in cookies) {
            val properties = mutableMapOf<Any?, Any?>(
                NSHTTPCookieName to cookie.name,
                NSHTTPCookieValue to cookie.value,
                NSHTTPCookieDomain to cookie.domain,
                NSHTTPCookiePath to cookie.path,
                NSHTTPCookieSecure to cookie.secure,
                "HttpOnly" to cookie.httpOnly,
            )
            if (cookie.persistent) {
                properties[NSHTTPCookieExpires] = NSDate(
                    timeIntervalSinceReferenceDate = cookie.expiresAt / 1000.0 - APPLE_REFERENCE_DATE_UNIX_SECONDS,
                )
            }
            NSHTTPCookie.cookieWithProperties(properties)?.let { setCookie(it) }
        }
    }

    override suspend fun loadForRequest(url: HttpUrl): List<Cookie> = withContext(Dispatchers.Main) {
        allCookies().mapNotNull { it.toKmpCookie() }.filter { it.matches(url) }
    }

    suspend fun exportSessionCookies(): List<SessionCookie> = withContext(Dispatchers.Main) {
        allCookies().mapNotNull { native ->
            val cookie = native.toKmpCookie() ?: return@mapNotNull null
            val domain = cookie.domain.removePrefix(".")
            if (!domain.endsWith("youtube.com") && !domain.endsWith("google.com")) return@mapNotNull null
            SessionCookie(
                name = cookie.name,
                value = cookie.value,
                domain = domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                expiresAt = cookie.expiresAt.takeIf { cookie.persistent },
            )
        }
    }

    private suspend fun allCookies(): List<NSHTTPCookie> = suspendCancellableCoroutine { continuation ->
        store.getAllCookies { cookies ->
            continuation.resume(cookies?.map { it as NSHTTPCookie }.orEmpty())
        }
    }

    private suspend fun setCookie(cookie: NSHTTPCookie) = suspendCancellableCoroutine { continuation ->
        store.setCookie(cookie) { continuation.resume(Unit) }
    }

    private fun NSHTTPCookie.toKmpCookie(): Cookie? {
        val cookieName = name.takeIf { it.isNotEmpty() } ?: return null
        val cookieDomain = domain.removePrefix(".").takeIf { it.isNotEmpty() } ?: return null
        val builder = CookieBuilder().name(cookieName).value(value).path(path)
        // WKHTTPCookieStore does not expose the host-only flag, and may normalize a Domain value
        // by dropping its leading dot. Treat its domain as a suffix pattern; matches() still rejects
        // unrelated hosts and mirrors Android CookieManager's URL-scoped reconstruction.
        builder.domain(cookieDomain)
        if (isSecure()) builder.secure()
        if (isHTTPOnly()) builder.httpOnly()
        expiresDate?.let { builder.expiresAt((it.timeIntervalSince1970 * 1000.0).toLong()) }
        return builder.build()
    }

    private companion object {
        const val APPLE_REFERENCE_DATE_UNIX_SECONDS = 978_307_200.0
    }
}
