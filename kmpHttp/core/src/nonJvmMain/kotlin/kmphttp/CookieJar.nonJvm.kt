package kmphttp

/**
 * Non-JVM [CookieJar]: a suspend-based policy/persistence interface (unlike OkHttp's blocking jar
 * that JVM aliases to). The public [saveFromResponse]/[loadForRequest] extensions delegate to these
 * members.
 */
actual interface CookieJar {
    suspend fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>)

    suspend fun loadForRequest(url: HttpUrl): List<Cookie>

    actual companion object {
        actual val NO_COOKIES: CookieJar = object : CookieJar {
            override suspend fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                // Reject all cookies.
            }

            override suspend fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }
    }
}

actual suspend fun CookieJar.saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
    saveFromResponse(url, cookies)

actual suspend fun CookieJar.loadForRequest(url: HttpUrl): List<Cookie> =
    loadForRequest(url)
