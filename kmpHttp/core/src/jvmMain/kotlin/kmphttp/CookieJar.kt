package kmphttp

actual typealias CookieJar = okhttp3.CookieJar

actual suspend fun CookieJar.saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    return this.saveFromResponse(url, cookies)
}

actual suspend fun CookieJar.loadForRequest(url: HttpUrl): List<Cookie> {
    return this.loadForRequest(url)
}
