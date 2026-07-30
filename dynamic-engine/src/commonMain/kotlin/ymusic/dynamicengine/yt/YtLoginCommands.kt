package ymusic.dynamicengine.yt

import ymusic.dynamicengine.DynamicEngineManager

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
        w.beginObject()
        w.endObject()
    }) { readLoginUrl(it) }
}

/**
 * The sign-in gate — COOKIE-ONLY, no network. The login screen polls this while the user signs in;
 * `true` means the essential cookies are present in both jars, so it is safe to dismiss, fetch the
 * account card, and refresh the feeds. It deliberately never touches the network: an authenticated
 * call against a half-set jar makes YouTube revoke the cookies.
 */
suspend fun DynamicEngineManager.ytLoginStatus(): Boolean = use { engine ->
    engine.jsonCommand("yt/login/status", { w ->
        w.beginObject()
        w.endObject()
    }) { readSignedIn(it) }
}

/**
 * The signed-in account card (name / email / handle / channel), from account_menu. Call only once
 * [ytLoginStatus] is true — a not-signed-in jar answers [YtAccountInfo.signedIn]=false with no
 * network call.
 */
suspend fun DynamicEngineManager.ytAccountInfo(): YtAccountInfo = use { engine ->
    engine.jsonCommand("yt/account", { w ->
        w.beginObject()
        w.endObject()
    }) { readAccount(it) }
}

/** Sign out — drops the auth cookies in both jars, keeping the anonymous identity (PREF/visitor/YSC). */
suspend fun DynamicEngineManager.ytLogout(): Unit = use { engine ->
    engine.jsonCommand("yt/logout", { w ->
        w.beginObject()
        w.endObject()
    }) { it.skipValue() }
}
