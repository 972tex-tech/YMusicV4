package io.ymusic.remoteconfig

import kmphttp.Cookie
import kmphttp.CookieJar
import kmphttp.HttpUrl

// CookieJar is the suspend interface on non-JVM; the store synchronizes itself (its critical
// sections are tiny and its file is a few hundred bytes, so briefly blocking a thread is fine).
actual class PersistentCookieJar actual constructor(document: CookieDocument) : CookieJar {
    private val store = PersistentCookieStore(document)

    override suspend fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = store.saveFromResponse(cookies)

    override suspend fun loadForRequest(url: HttpUrl): List<Cookie> = store.loadForRequest(url)
}
