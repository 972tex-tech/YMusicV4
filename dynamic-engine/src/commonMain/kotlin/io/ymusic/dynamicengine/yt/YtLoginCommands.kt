package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngineManager

/**
 * The YouTube sign-in surface — intent-level twins of the engine bundle's `yt/login/…` + `yt/account`
 * commands (engine-js `logic/yt/login.ts`). The app drives a browser WebView through Google's sign-in
 * (the WebView writes cookies into the same host jar the engine reads); these calls are how it learns
 * the outcome and the account identity. Same pipe contract as [YtCommands] — no reflective serializer.
 */

/**
 * The sign-in entry URL — engine-owned so it ships with the bundle, never hardcoded app-side. The
 * app opens a browser WebView on it; the `Logout?continue=` form forces a fresh Google account
 * chooser.
 */
suspend fun DynamicEngineManager.ytLoginUrl(): String = use { engine ->
    engine.jsonCommand("yt/login/url", { w ->
    }) { readLoginUrl(it) }
}

/**
 * The account-switcher entry URL (YouTube's channel switcher) — opened like the sign-in browser but
 * with NO cookie polling: the switcher swaps identities within an already-valid jar, so there is no
 * gate to flip. The app refreshes the session and account card when the user closes the screen.
 */
suspend fun DynamicEngineManager.ytSwitchAccountUrl(): String = use { engine ->
    engine.jsonCommand("yt/login/switch-url", { w ->
    }) { readLoginUrl(it) }
}

/**
 * Where the user clears a [io.ymusic.dynamicengine.EngineError.Captcha] bot challenge. Open a browser
 * on it with its User-Agent; no gate to poll — closing the browser is completion, then
 * [ytRefreshSession] and retry.
 */
suspend fun DynamicEngineManager.ytVerifyTarget(): YtVerifyTarget = use { engine ->
    engine.jsonCommand("yt/verify/target", { w ->
    }) { readVerifyTarget(it) }
}

/**
 * The sign-in gate — COOKIE-ONLY, no network. The login screen polls this while the user signs in;
 * `true` means the essential cookies are present in both jars, so it is safe to dismiss, fetch the
 * account card, and refresh the feeds. It deliberately never touches the network: an authenticated
 * call against a half-set jar makes YouTube revoke the cookies.
 */
suspend fun DynamicEngineManager.ytLoginStatus(): Boolean = use { engine ->
    engine.jsonCommand("yt/login/status", { w ->
    }) { readSignedIn(it) }
}

/**
 * The signed-in account card (name / email / handle / channel), from account_menu. Cached-first: a
 * signed-in engine with a cached card returns it with no network — pass [refresh]=true (on a fresh
 * sign-in) to force account_menu and re-cache. A not-signed-in jar answers
 * [YtAccountInfo.signedIn]=false with no call. Call only once [ytLoginStatus] is true.
 */
suspend fun DynamicEngineManager.ytAccountInfo(refresh: Boolean = false): YtAccountInfo = use { engine ->
    engine.jsonCommand("yt/account", { w ->
        if (refresh) w.name("refresh").value(true)
    }) { readAccount(it) }
}

/** Sign out — drops the auth cookies in both jars, keeping the anonymous identity (PREF/visitor/YSC). */
suspend fun DynamicEngineManager.ytLogout(): Unit = use { engine ->
    engine.jsonCommand("yt/logout", { w ->
    }) { it.skipValue() }
}

/**
 * Drop the cached session and re-scrape under the current cookie identity — call right after
 * [ytLoginStatus] flips (sign-in), after [ytLogout], or after the account switcher closes so the
 * engine's authenticated headers pick up (or drop) SESSION_INDEX / delegated session. A homepage
 * GET, safe once the cookie gate has passed.
 */
suspend fun DynamicEngineManager.ytRefreshSession(): Unit = use { engine ->
    engine.jsonCommand("yt/session/refresh", { w ->
    }) { it.skipValue() }
}
