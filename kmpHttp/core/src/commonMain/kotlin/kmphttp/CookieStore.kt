package kmphttp

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-memory cookie storage shared by the platform [InMemoryCookieJar] actuals. Not thread-safe on
 * its own — the actuals serialize access (JVM via @Synchronized, native via a Mutex).
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
