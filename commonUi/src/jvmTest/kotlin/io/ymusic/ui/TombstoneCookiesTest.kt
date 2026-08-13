package io.ymusic.ui

import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.extractMusicLibrary
import kmphttp.CookieBuilder
import kmphttp.saveFromResponse
import kmphttp.toHttpUrl
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The half-wiped jar seen live 2026-07-31: the classic auth names tombstoned to EMPTY values
 * ("SAPISID=") while the __Secure twins survived with real values. The engine must treat empty
 * cookies as absent — not emit them on requests — and still authenticate off the __Secure twins
 * (SAPISID is normally a copy of __Secure-3PAPISID). Regression: the empty SAPISID used to kill
 * the Authorization header entirely, turning every engine call anonymous ("library empty" while
 * the browser on the same jar was signed in).
 */
class TombstoneCookiesTest {

    @Test
    fun empty_tombstones_are_dropped_and_auth_falls_back_to_the_secure_twins() {
        Locale.setDefault(Locale.ENGLISH)
        val headers = CopyOnWriteArrayList<Pair<String?, String?>>() // Cookie to Authorization
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei") -> {
                    headers += request.header("Cookie") to request.header("Authorization")
                    textResponse(request, "{}", "application/json")
                }
                else -> textResponse(request, "{}", "application/json")
            }
        }
        runBlocking {
            fun cookie(name: String, value: String) = CookieBuilder()
                .name(name).value(value).domain("youtube.com").path("/").build()
            client.cookieJar.saveFromResponse(
                "https://www.youtube.com/".toHttpUrl(),
                listOf(
                    cookie("SAPISID", ""), // the tombstone
                    cookie("LOGIN_INFO", "live-login-info"),
                    cookie("__Secure-1PAPISID", "papisid-value"),
                    cookie("__Secure-3PAPISID", "papisid-value"),
                ),
            )
            val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
            try {
                engines.start()
                engines.extractMusicLibrary()
            } finally {
                engines.close()
            }
        }
        assertTrue(headers.isNotEmpty())
        val (cookieHeader, authorization) = headers.first()
        // The tombstone never rides; the live cookies do.
        assertTrue(cookieHeader != null && !cookieHeader.contains("SAPISID=;") && !cookieHeader.endsWith("SAPISID="), "$cookieHeader")
        assertTrue(cookieHeader.contains("__Secure-3PAPISID=papisid-value"), "$cookieHeader")
        // Auth survives off the twins instead of dropping to anonymous.
        assertTrue(authorization != null && authorization.contains("SAPISIDHASH"), "$authorization")
    }
}
