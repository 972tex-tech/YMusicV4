package io.ymusic.ios

import kmphttp.Cookie
import kmphttp.CookieBuilder
import kmphttp.CookieJar
import kmphttp.toHttpUrlOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPCookie
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

private val HARVEST_HOSTS = setOf("youtube.com", "google.com")

@OptIn(ExperimentalForeignApi::class)
internal suspend fun harvestWkCookiesInto(jar: CookieJar) {
    val store = WKWebsiteDataStore.defaultDataStore().httpCookieStore
    val native = suspendCancellableCoroutine<List<NSHTTPCookie>> { cont ->
        store.getAllCookies { cookies ->
            cont.resume(cookies?.map { it as NSHTTPCookie }.orEmpty())
        }
    }
    val persistUntil = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong() +
        400L * 24 * 60 * 60 * 1000
    for (n in native) {
        val host = n.domain.removePrefix(".")
        if (HARVEST_HOSTS.none { host == it || host.endsWith(".$it") }) continue
        val url = ("https://" + host.trimStart('.') + "/").toHttpUrlOrNull() ?: continue
        val k = n.toKmpCookie(persistUntil) ?: continue
        jar.saveFromResponse(url, listOf(k))
    }
}

private fun NSHTTPCookie.toKmpCookie(persistUntil: Long): Cookie? {
    val cookieName = name.takeIf { it.isNotEmpty() } ?: return null
    val cookieDomain = domain.removePrefix(".").takeIf { it.isNotEmpty() } ?: return null
    val builder = CookieBuilder().name(cookieName).value(value).path(path).domain(cookieDomain)
    if (isSecure()) builder.secure()
    if (isHTTPOnly()) builder.httpOnly()
    val exp = expiresDate?.let { (it.timeIntervalSince1970 * 1000.0).toLong() } ?: persistUntil
    builder.expiresAt(exp)
    return builder.build()
}
