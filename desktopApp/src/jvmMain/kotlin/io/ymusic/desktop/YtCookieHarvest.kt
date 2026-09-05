package io.ymusic.desktop

import kmphttp.CookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val HARVEST_URLS = listOf(
    "https://www.youtube.com/",
    "https://m.youtube.com/",
    "https://accounts.google.com/",
)

/** Copy the login WebView's YouTube cookies into [jar]. No-op when CEF's manager is not up. */
fun harvestCefCookiesInto(jar: CookieJar) {
    val manager = CefCookieManager.getGlobalManager() ?: return
    for (raw in HARVEST_URLS) {
        val url = raw.toHttpUrl()
        val cookies = visit(manager, raw)
        if (cookies.isNotEmpty()) jar.saveFromResponse(url, cookies)
    }
}

private fun visit(manager: CefCookieManager, url: String): List<Cookie> {
    val out = CopyOnWriteArrayList<Cookie>()
    val done = CountDownLatch(1)
    val started = manager.visitUrlCookies(
        url,
        true,
        object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                toOkHttp(cookie)?.let { out += it }
                if (count + 1 >= total) done.countDown()
                return true
            }
        },
    )
    if (started) done.await(1, TimeUnit.SECONDS)
    return out.toList()
}

private fun toOkHttp(c: CefCookie): Cookie? {
    val name = c.name?.takeIf { it.isNotEmpty() } ?: return null
    val rawDomain = c.domain?.takeIf { it.isNotEmpty() } ?: return null
    val builder = Cookie.Builder().name(name).value(c.value ?: "").path(c.path ?: "/")
    if (rawDomain.startsWith(".")) builder.domain(rawDomain.removePrefix("."))
    else builder.hostOnlyDomain(rawDomain.removePrefix("."))
    if (c.secure) builder.secure()
    if (c.httponly) builder.httpOnly()
    val expires = c.expires
    builder.expiresAt(
        expires?.time ?: (System.currentTimeMillis() + 400L * 24 * 60 * 60 * 1000),
    )
    return builder.build()
}
