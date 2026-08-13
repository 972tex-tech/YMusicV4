package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kmphttp.CookieJar
import kmphttp.InMemoryCookieJar
import kmphttp.Request
import kmphttp.Response
import kmphttp.parseCookie
import kmphttp.saveFromResponse
import kmphttp.toHttpUrlOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The YouTube sign-in commands end to end over the PRODUCTION bundle: the cookie-only gate reads the
 * seeded jar (no HTTP), account_menu parses the account card, and the authenticated call carries the
 * SAPISIDHASH the engine computes from the jar's SAPISID.
 */
class YtLoginCommandsTest {

    private var authHeader: String? = null
    private var authUserHeader: String? = null
    private var bootstrapHeader: String? = null

    private suspend fun manager(
        jar: CookieJar?,
        cacheStore: io.ymusic.dynamicengine.CacheStore? = null,
        handler: suspend (Request) -> Response,
    ): DynamicEngineManager {
        authHeader = null
        authUserHeader = null
        bootstrapHeader = null
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                handler(request)
            }
        }
        if (jar != null) client.cookieJar = jar
        return DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = cacheStore).also { it.start() }
    }

    /** A wiped, deterministic on-disk cache (SQLite index + temp blobs on a ticking clock). */
    private fun cacheStore(): io.ymusic.dynamicengine.DiskCacheStore {
        val dir = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-yt-account-test"
        okio.FileSystem.SYSTEM.deleteRecursively(dir)
        var tick = 1_000L
        return io.ymusic.dynamicengine.DiskCacheStore(
            io.ymusic.dynamicengine.testCacheDriver(),
            dir,
            okio.FileSystem.SYSTEM,
            now = { tick++ },
        )
    }

    /** A jar carrying the essential sign-in cookies (in the shared .youtube.com scope) plus SAPISID. */
    private suspend fun signedInJar(): CookieJar {
        val jar = InMemoryCookieJar()
        val url = "https://www.youtube.com/".toHttpUrlOrNull()!!
        val setCookies = listOf(
            "LOGIN_INFO=abc; Domain=.youtube.com; Path=/",
            "SIDCC=def; Domain=.youtube.com; Path=/",
            "SSID=ghi; Domain=.youtube.com; Path=/",
            "SAPISID=sapisidvalue; Domain=.youtube.com; Path=/",
        ).mapNotNull { parseCookie(url, it) }
        jar.saveFromResponse(url, setCookies)
        return jar
    }

    private fun jsonResponse(body: String) =
        CannedResponse.ofText(body, headers = listOf("Content-Type" to "application/json"))

    @Test
    fun loginUrlIsTheGoogleLogoutFlow() = runTest {
        val m = manager(null) { error("no HTTP expected for the login URL") }
        assertEquals(
            "https://accounts.google.com/Logout?continue=https://m.youtube.com/signin?app=mobile%26persist_app=1",
            m.ytLoginUrl(),
        )
    }

    @Test
    fun statusIsFalseWithoutAuthCookies() = runTest {
        val m = manager(InMemoryCookieJar()) { error("the gate is cookie-only — no HTTP") }
        assertFalse(m.ytLoginStatus())
    }

    @Test
    fun statusIsTrueWithEssentialCookies() = runTest {
        val m = manager(signedInJar()) { error("the gate is cookie-only — no HTTP") }
        assertTrue(m.ytLoginStatus())
    }

    @Test
    fun accountParsesTheCardAndSendsSapisidHash() = runTest {
        val m = manager(signedInJar()) { request ->
            if (request.url.toString().contains("account/account_menu")) {
                authHeader = request.header("Authorization")
                authUserHeader = request.header("X-Goog-AuthUser")
                bootstrapHeader = request.header("X-Youtube-Bootstrap-Logged-In")
                jsonResponse(LOGIN_ACCOUNT_MENU_FIXTURE).build(request)
            } else {
                error("unexpected request: ${request.url}")
            }
        }
        val account = m.ytAccountInfo()
        assertTrue(account.signedIn)
        assertEquals("Mock User", account.name)
        assertEquals("mockuser@example.com", account.email)
        assertEquals("@mockuser", account.handle)
        assertEquals("UCabc123", account.channelId)
        assertEquals(1, account.thumbs.size)
        // The authenticated youtubei call must carry the multi-hash SAPISIDHASH the engine computes.
        val auth = authHeader
        assertNotNull(auth, "account_menu carried no Authorization header")
        assertTrue(auth.startsWith("SAPISIDHASH "), "expected SAPISIDHASH auth, got: $auth")
        // The anonymous homepage fixture carries no SESSION_INDEX, so X-Goog-AuthUser must be OMITTED
        // — never a hardcoded "0" (legacy sends the real index or nothing).
        assertNull(authUserHeader, "X-Goog-AuthUser must be omitted when the session index is unknown")
        // A signed-in request advertises it like the web client does.
        assertEquals("true", bootstrapHeader, "X-Youtube-Bootstrap-Logged-In must be sent when signed in")
        // No USER_SESSION_ID in the fixture, so the plain (no-"_u") hash form.
        assertFalse(auth.contains("_u"), "no USER_SESSION_ID → no _u suffix; got: $auth")
    }

    @Test
    fun accountIsServedFromCacheThenRefetchedOnRefresh() = runTest {
        var menuHits = 0
        val m = manager(signedInJar(), cacheStore()) { request ->
            if (request.url.toString().contains("account/account_menu")) {
                menuHits++
                jsonResponse(LOGIN_ACCOUNT_MENU_FIXTURE).build(request)
            } else {
                error("unexpected request: ${request.url}")
            }
        }
        val first = m.ytAccountInfo() // miss → fetch + cache
        val second = m.ytAccountInfo() // hit → no network
        assertEquals("Mock User", first.name)
        assertEquals(first.name, second.name)
        assertEquals(1, menuHits, "the second fetch must come from cache, not account_menu")
        m.ytAccountInfo(refresh = true)
        assertEquals(2, menuHits, "refresh=true must re-fetch account_menu")
    }
}

private val LOGIN_ACCOUNT_MENU_FIXTURE = """
{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
  "header":{"activeAccountHeaderRenderer":{
    "accountName":{"simpleText":"Mock User"},
    "email":{"simpleText":"mockuser@example.com"},
    "channelHandle":{"simpleText":"@mockuser"},
    "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/photo.jpg","width":48,"height":48}]}
  }},
  "sections":[{"multiPageMenuSectionRenderer":{"items":[
    {"compactLinkRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc123"}}}}
  ]}}]
}}}}]}
""".trimIndent()
