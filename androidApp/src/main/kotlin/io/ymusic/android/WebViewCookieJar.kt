package io.ymusic.android

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * A [CookieJar] backed by the Android WebView [CookieManager] — the single cookie store shared by
 * the scraping engine's HTTP, the MSE YouTube player, and the login WebView. So the anonymous
 * visitor identity (VISITOR_INFO1_LIVE / YSC / PREF / CONSENT / SOCS) the player earns while playing
 * is the same one the InnerTube calls send, and vice versa — and it persists across launches for
 * free (the WebView store is on disk).
 *
 * On Android `kmphttp.CookieJar` *is* `okhttp3.CookieJar` and `kmphttp.Cookie` *is* `okhttp3.Cookie`,
 * so this plugs straight into `KmpHttpClient.create { cookieJar = … }` and the engine's shared jar.
 */
class WebViewCookieJar : CookieJar {

    private val manager: CookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        // Each cookie's Set-Cookie string (name=value; expires=…; domain=…; path=…; secure; httponly)
        // is what CookieManager.setCookie expects — the same shape the browser stores.
        for (cookie in cookies) manager.setCookie(u, cookie.toString())
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // getCookie returns the request-header form "name=value; name2=value2" (no attributes),
        // already scoped to the URL by the store; rebuild Cookie objects for okhttp to emit.
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) null
            else Cookie.Builder().name(name).value(value).domain(url.host).build()
        }
    }
}
