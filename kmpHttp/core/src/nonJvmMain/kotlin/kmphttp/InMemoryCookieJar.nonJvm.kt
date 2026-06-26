package kmphttp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// CookieJar is the suspend interface on non-JVM; serialize access with a Mutex.
actual class InMemoryCookieJar actual constructor() : CookieJar {
    private val store = CookieStore()
    private val mutex = Mutex()

    override suspend fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
        mutex.withLock { store.saveFromResponse(cookies) }

    override suspend fun loadForRequest(url: HttpUrl): List<Cookie> =
        mutex.withLock { store.loadForRequest(url) }
}
