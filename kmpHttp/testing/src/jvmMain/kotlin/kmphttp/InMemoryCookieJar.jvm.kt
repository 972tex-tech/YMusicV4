package kmphttp

// CookieJar == okhttp3.CookieJar on JVM (blocking); serialize access with @Synchronized.
actual class InMemoryCookieJar actual constructor() : CookieJar {
    private val store = CookieStore()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
        store.saveFromResponse(cookies)

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.loadForRequest(url)
}
