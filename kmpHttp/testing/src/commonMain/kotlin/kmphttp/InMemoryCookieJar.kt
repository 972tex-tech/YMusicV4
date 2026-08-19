package kmphttp

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A simple in-memory [CookieJar]: accepts all cookies, keeps them in memory, and returns the
 * matching, unexpired ones for each request. Session (non-persistent) cookies live for the lifetime
 * of this instance; nothing is persisted across process restarts.
 *
 * Test support, not production surface — real jars are WebView-backed (or [CookieJar.NO_COOKIES]).
 * expect/actual because [CookieJar] itself differs per platform (okhttp's blocking interface on
 * the JVM, a suspend interface elsewhere); [CookieStore] below is the one shared implementation
 * both thin actuals lock around.
 */
expect class InMemoryCookieJar() : CookieJar

/**
 * The common half of [InMemoryCookieJar]: replace-by-(name, domain, path), expiry, URL matching.
 * Not thread-safe on its own — the actuals serialize access (JVM via @Synchronized, native via a
 * Mutex).
 */
@OptIn(ExperimentalTime::class)
internal class CookieStore {
    private val cookies = mutableListOf<Cookie>()

    fun saveFromResponse(newCookies: List<Cookie>) {
        val now = Clock.System.now().toEpochMilliseconds()
        for (cookie in newCookies) {
            cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            // Keep session cookies (non-persistent) and not-yet-expired persistent cookies.
            if (!cookie.persistent || cookie.expiresAt > now) cookies.add(cookie)
        }
    }

    fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = Clock.System.now().toEpochMilliseconds()
        cookies.removeAll { it.persistent && it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}
