package ymusic.player

import kmphttp.KmpHttpClient
import kmphttp.create
import platform.AVFoundation.AVPlayer

/**
 * Swift-facing helpers so a native (SwiftUI) app can drive `:player` through `Player.framework`
 * without wrestling Kotlin generics/DSLs. Covers both engines: the AVFoundation [LocalPlayer] and the
 * WKWebView [WebViewPlayer].
 */

/** The underlying `AVPlayer` — hand it to an `AVPlayerLayer` to display video. Null for non-Apple. */
fun LocalPlayer.avPlayer(): AVPlayer? = (this as? AppleLocalPlayer)?.player0

/** A plain snapshot of the current status (avoids exposing the `StateFlow` generic to Swift). */
fun MediaEngine.currentStatus(): PlaybackStatus = status.value

/** A plain snapshot of the current play-head (position/duration/buffered). */
fun MediaEngine.currentProgress(): Progress = progress.value

/**
 * Convenience for Apple apps: a WKWebView YouTube [WebViewPlayer] with a default NSURLSession-backed
 * HTTP client (the [context] carries the host view container). Use [createWebViewPlayer] directly to
 * supply your own [KmpHttpClient] (cookies/cache).
 */
fun createYtWebViewPlayer(
    context: WebViewPlayerContext,
    engineJs: String,
    appVersion: Int,
): WebViewPlayer = createWebViewPlayer(context, engineJs, appVersion, KmpHttpClient.create {})
