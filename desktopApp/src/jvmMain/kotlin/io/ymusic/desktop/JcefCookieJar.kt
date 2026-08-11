package io.ymusic.desktop

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [CookieJar] backed by JCEF's global [CefCookieManager] — the single cookie store shared by the
 * scraping engine's HTTP (this jar on the `KmpHttpClient`) and the JCEF WebView YouTube player,
 * mirroring Android's `WebViewCookieJar`. So the anonymous visitor identity (VISITOR_INFO1_LIVE /
 * YSC / PREF / CONSENT / SOCS) is the same for InnerTube calls and the player, and persists on disk
 * via JCEF's cookie store.
 *
 * Only ever used on the engine's `KmpHttpClient` requests (InnerTube scraping + the one embed fetch,
 * both to youtube.com — which have cookies, so the visitor fires immediately); the WebView's own
 * segment fetches go through CEF directly. So the bounded blocking read below is off the hot path.
 * [CefCookieManager.getGlobalManager] requires CefApp to be initialized first — see [Main] ordering.
 */
class JcefCookieJar(private val manager: CefCookieManager) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        var deleted = false
        for (c in cookies) {
            // An already-expired cookie is a DELETION (the engine's sign-out sends these). Setting
            // an expired CefCookie is unreliable for it — deleteCookies matches by url+name and
            // hits the stored cookie whatever its domain/secure form.
            if (c.persistent && c.expiresAt <= System.currentTimeMillis()) {
                manager.deleteCookies(u, c.name)
                deleted = true
                continue
            }
            val expires = if (c.persistent) Date(c.expiresAt) else null
            // okhttp strips the leading dot a domain cookie carries; CefCookie's struct form (unlike
            // header parsing) needs it back, or Chromium stores a host-only twin NEXT TO the real
            // ".youtube.com" cookie instead of updating it.
            val domain = if (c.hostOnly) c.domain else "." + c.domain
            // Chromium rejects a __Secure-/__Host- write without the Secure bit, even over https.
            val secure = c.secure || c.name.startsWith("__Secure-") || c.name.startsWith("__Host-")
            manager.setCookie(
                u,
                CefCookie(c.name, c.value, domain, c.path, secure, c.httpOnly, Date(), Date(), expires != null, expires),
            )
        }
        // CEF persists its cookie DB LAZILY, and the app can die by Runtime.halt (the terminate
        // watchdog) before it ever flushes — a sign-out's deletions would silently roll back on
        // the next launch (seen live 2026-07-31: the full auth set resurrected). Deletions are the
        // semantically critical writes, so force them to disk; routine rotations self-heal.
        if (deleted) manager.flushStore(null)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val out = CopyOnWriteArrayList<Cookie>()
        val done = CountDownLatch(1)
        val started = manager.visitUrlCookies(
            url.toString(),
            true,
            object : CefCookieVisitor {
                override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                    cookie.name?.let { name ->
                        out += Cookie.Builder().name(name).value(cookie.value ?: "").domain(url.host).build()
                    }
                    if (count + 1 >= total) done.countDown()
                    return true
                }
            },
        )
        // visitor callbacks land on a CEF thread; this runs on an OkHttp thread, so the wait is safe.
        // Bounded so a cookie-less URL (visitor never fires) can't hang the request.
        if (started) done.await(1, TimeUnit.SECONDS)
        return out.toList()
    }
}
