package io.ymusic.android

import android.webkit.CookieManager
import kmphttp.CookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val HARVEST_URLS = listOf(
    "https://www.youtube.com/",
    "https://m.youtube.com/",
    "https://accounts.google.com/",
)

/** Copy the login WebView's YouTube cookies into [jar]. HttpOnly names are included. */
fun harvestWebViewCookiesInto(jar: CookieJar) {
    val manager = CookieManager.getInstance()
    manager.flush()
    for (raw in HARVEST_URLS) {
        val url = raw.toHttpUrlOrNull() ?: continue
        val header = manager.getCookie(raw) ?: continue
        val cookies = parseCookieHeader(url, header)
        if (cookies.isNotEmpty()) jar.saveFromResponse(url, cookies)
    }
}

private fun parseCookieHeader(url: HttpUrl, header: String): List<Cookie> {
    val persistUntil = System.currentTimeMillis() + 400L * 24 * 60 * 60 * 1000
    return header.split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty()) return@mapNotNull null
        Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain(url.host)
            .expiresAt(persistUntil)
            .build()
    }
}
