package io.ymusic.remoteconfig

import kmphttp.Cookie
import kmphttp.CookieJar
import kmphttp.HttpUrl
import okio.FileSystem
import okio.Path

// CookieJar == okhttp3.CookieJar on JVM (blocking); the store synchronizes itself.
actual class PersistentCookieJar actual constructor(file: Path, fileSystem: FileSystem) : CookieJar {
    private val store = PersistentCookieStore(file, fileSystem)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = store.saveFromResponse(cookies)

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.loadForRequest(url)
}
