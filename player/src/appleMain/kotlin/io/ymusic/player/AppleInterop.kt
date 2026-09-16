package io.ymusic.player

import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.create
import kmphttp.utf8String
import platform.AVFoundation.AVPlayer

/**
 * Swift-facing helpers so a native (SwiftUI) app can drive `:player` through `Player.framework`
 * without wrestling Kotlin generics/DSLs. Covers both engines: the AVFoundation [LocalPlayer] and the
 * WKWebView [WebViewPlayer].
 */

/** The underlying `AVPlayer` — hand it to an `AVPlayerLayer` to display video. Null for non-Apple. */
fun LocalPlayer.avPlayer(): AVPlayer? = (this as? AppleLocalPlayer)?.player0

/** A plain snapshot of the current status (avoids exposing the `StateFlow` generic to Swift). */
fun MediaEngine<*>.currentStatus(): PlaybackStatus = status.value

/** A plain snapshot of the current play-head (position/duration/buffered). */
fun MediaEngine<*>.currentProgress(): Progress = progress.value

/**
 * Swift-friendly [PlaybackConfig] factory — Swift can't ergonomically build the `Pair<String, Long>`
 * preference fields, so it passes the three common knobs and audio-only maps to
 * audio-only is [PlaybackConfig.audioOnly], not a quality token.
 */
fun playbackConfig(volume: Float = 1f, speed: Float = 1f, audioOnly: Boolean = false): PlaybackConfig =
    PlaybackConfig(
        audioOnly = audioOnly,
        volume = volume,
        speed = speed,
    )

/**
 * Convenience for the Swift player labs: a WKWebView YouTube [WebViewPlayer] whose embed page comes
 * from a lab-local direct fetch on a default NSURLSession-backed client (the [context] carries the
 * host view container). App shells use [createWebViewPlayer] with the scraping engine's `yt/embed`
 * instead — remotely updatable rewrite, shared cookie identity; the fetch+rewrite below is a
 * dev-harness duplicate of engine-js `logic/yt/embed.ts` so the labs need no engine.
 */
fun createYtWebViewPlayer(
    context: WebViewPlayerContext,
    engineJs: String,
    appVersion: Int,
): WebViewPlayer = createWebViewPlayer(context, engineJs, appVersion, labEmbedHtml(KmpHttpClient.create {}))

private fun labEmbedHtml(httpClient: KmpHttpClient): suspend (userAgent: String) -> String = { userAgent ->
    val resp = httpClient.execute(
        RequestBuilder()
            .header("User-Agent", userAgent)
            .url("https://www.youtube.com/embed")
            .build(),
    )
    val html = try {
        check(resp.isSuccessful) { "HTTP ${resp.code}" }
        resp.body.utf8String()
    } finally {
        resp.close()
    }
    prepareLabEmbedHtml(html)
}

private val cspMeta = Regex("""(<meta[^>]*http-equiv=["'])content-security-policy(["'])""", RegexOption.IGNORE_CASE)
private val scriptPreload = Regex("""(<link\b[^>]*\bas=["'])script(["'])""", RegexOption.IGNORE_CASE)
private val headOpen = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val bodyOpen = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)

private fun prepareLabEmbedHtml(html: String): String {
    val rewritten = html
        .let { cspMeta.replace(it) { m -> "${m.groupValues[1]}content-security-policy-disabled${m.groupValues[2]}" } }
        .let { scriptPreload.replace(it) { m -> "${m.groupValues[1]}script-blocked${m.groupValues[2]}" } }
    val tag = "<script>\nINJECT_JS_HERE\n</script>"
    val anchor = headOpen.find(rewritten) ?: bodyOpen.find(rewritten)
    return if (anchor != null) {
        val at = anchor.range.last + 1
        rewritten.substring(0, at) + tag + rewritten.substring(at)
    } else {
        tag + rewritten
    }
}
