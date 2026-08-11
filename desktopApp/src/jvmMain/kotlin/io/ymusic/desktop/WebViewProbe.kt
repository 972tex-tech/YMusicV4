package io.ymusic.desktop

import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.yt.embedPage
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.VideoQualityState
import io.ymusic.player.WebViewPlayer
import io.ymusic.player.WebViewPlayerContext
import io.ymusic.player.createWebViewPlayer
import kmphttp.KmpHttpClient
import kmphttp.create
import kmphttp.utf8String
import kotlinx.coroutines.runBlocking
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.JcefOsrSurface
import org.cef.handler.CefDisplayHandlerAdapter
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Headless-ish diagnostic for the JVM WebView YouTube engine: wires the exact desktop engine surface
 * (a shared OverlayLayout container) and drives a muted [WebViewPlayer.load], polling `status`/
 * `progress`. Two modes reproduce the desktop bug:
 *   - `realized`  — the container is hosted in a shown JFrame (peer realized) → the browser loads
 *                   and playback advances (position > 0).
 *   - `headless`  — the container is never added to a shown window → a *windowed* JCEF browser never
 *                   creates its native browser, so the page never loads and position stays 0.
 *   - `next`      — the recommended-feed harvest end to end: prints the page-side preconditions the
 *                   bundle gates on and every watch-next report that survives the bridge, then fails
 *                   the run if a music video produced none.
 *   - `nextdirect` — `next`, but the embed page comes from a direct fetch + local rewrite instead of
 *                   the engine's `yt/embed` (A/B isolation when the engine-served page is suspect).
 *   - `embed`     — no WebView at all: just the engine's `yt/embed` round trip (length, timing, slot).
 * Args: `mode [videoId] [windowSeconds]` — use a 75s+ window to clear the ~48s attestation check.
 * Run: `./gradlew :desktopApp:webviewProbe -PprobeArgs="realized"` (or `"headless"`). Muted (volume 0)
 * per the test-audio rule; muted also lets YouTube autoplay without a user gesture.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "realized"
    val realized = mode != "headless"
    val videoId = args.getOrNull(1) ?: "dQw4w9WgXcQ"
    // Optional third arg: the watch window in seconds (default 30; use 75+ to clear the ~48s
    // googlevideo attestation window when verifying playback identity end to end).
    val windowMs = (args.getOrNull(2)?.toLongOrNull() ?: 30L) * 1000L
    println("[probe] mode=$mode realized=$realized videoId=$videoId")

    val httpClient: KmpHttpClient = KmpHttpClient.create {}
    // The embed page now comes from the scraping engine (yt/embed) — boot the baseline bundle.
    val probeLogger = object : kmpcommon.log.Logger {
        override fun log(level: kmpcommon.log.Logger.Level, tags: List<String>, message: String, error: Throwable?) {
            println("[engine] $level ${tags.joinToString("/")}: $message" + (error?.let { " (${it.message})" } ?: ""))
        }
    }
    val engines = DynamicEngineManager(httpClient, appVersion = 1, logger = probeLogger)
    runBlocking { engines.start() }
    if (mode == "embed") {
        // Diagnostic: just the engine's yt/embed round trip — no WebView at all.
        val t0 = System.currentTimeMillis()
        val html = runBlocking { engines.embedPage("Mozilla/5.0 (probe) AppleWebKit/537.36") }
        println("[probe] EMBED ${html.length} chars in ${System.currentTimeMillis() - t0}ms slot=${html.contains("INJECT_JS_HERE")}")
        println("[probe] EMBED head: " + html.take(700).replace('\n', ' '))
        runBlocking { engines.close() }
        exitProcess(if (html.contains("INJECT_JS_HERE")) 0 else 2)
    }
    val cef = CefManager()
    // The probe's own persistent profile, distinct from the app's jcef-cache: Chromium's
    // per-profile singleton lock means sharing the app's dir silently yields to a running app
    // instance and the probe browser never comes up.
    val probeProfile = File(System.getProperty("user.home"), ".ymusic/jcef-probe-cache")
    val cefClient = runBlocking { cef.createClient(cacheDir = probeProfile) }
    val webViewJs = readWebViewJsProbe() + if (mode.startsWith("next")) NEXT_DIAG_JS else ""
    if (mode.startsWith("next")) {
        cefClient.addDisplayHandler(
            object : CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int,
                ): Boolean {
                    if (message != null && message.startsWith("[diag]")) println("[page] $message")
                    return false
                }
            },
        )
    }

    val surface = JcefOsrSurface()
    val container = JPanel().apply { layout = OverlayLayout(this) }
    var frame: JFrame? = null
    lateinit var player: WebViewPlayer

    SwingUtilities.invokeAndWait {
        if (realized) {
            // `tiny` mimics the parked persistent surface (a ~1px realized SwingPanel while Now
            // Playing is closed): confirm a tiny-but-realized host still plays audio (no throttling).
            val dim = when (mode) {
                "tiny" -> Dimension(4, 4)
                "small" -> Dimension(160, 90)
                "mini" -> Dimension(64, 36)
                else -> Dimension(480, 270)
            }
            frame = JFrame("WebView probe").apply {
                contentPane.add(container)
                size = dim
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
        player = createWebViewPlayer(
            WebViewPlayerContext(cefClient, surface),
            engineJs = webViewJs,
            appVersion = 1,
            embedHtml = if (mode == "nextdirect") {
                directProbeEmbedHtml(httpClient)
            } else {
                { ua -> engines.embedPage(ua) }
            },
        )
        // Muted (position advancing is the "is it playing" signal). `audioonly` exercises the
        // music-default path: audio-only must still play (the WebView stays shown on its thumbnail —
        // it disables the video track, not the surface).
        val quality = if (mode == "audioonly") VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO
        player.load(
            PlayableItem.YtMedia(videoId),
            startPositionMs = 0L,
            playWhenReady = true,
            config = PlaybackConfig(preferVideoQuality = quality to 0L, volume = 0f),
        )
    }

    val harvests = java.util.concurrent.atomic.AtomicInteger()
    if (mode.startsWith("next")) {
        Thread {
            runBlocking {
                player.watchNextSeen.collect {
                    val astral = it.contentsJson.count { c -> c.isHighSurrogate() }
                    println(
                        "[probe] watchNextSeen #${harvests.incrementAndGet()} videoId=${it.videoId} " +
                            "chars=${it.contentsJson.length} astral=$astral",
                    )
                }
            }
        }.apply { isDaemon = true }.start()
    }

    val start = System.currentTimeMillis()
    var maxPos = 0L
    var reparented = false
    var posBeforeReparent = -1L
    while (System.currentTimeMillis() - start < windowMs) {
        val s = player.status.value
        val p = player.progress.value
        if (p.positionMs > maxPos) maxPos = p.positionMs
        val t = (System.currentTimeMillis() - start) / 1000
        // In `reparent` mode, after ~12s move the container to a fresh shown JFrame (removeNotify →
        // addNotify) and watch whether playback continues from where it was or the page reloads (pos
        // drops back near 0). This decides whether Compose may freely move the surface's SwingPanel.
        if (mode == "reparent" && !reparented && t >= 12) {
            reparented = true
            posBeforeReparent = p.positionMs
            println("[probe] --- reparenting at pos=$posBeforeReparent ---")
            SwingUtilities.invokeAndWait {
                frame?.contentPane?.remove(container)
                frame?.dispose()
                frame = JFrame("WebView probe (reparented)").apply {
                    contentPane.add(container)
                    size = Dimension(480, 270)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
            }
        }
        println("[probe] t=${t}s state=${s.state} pwr=${s.playWhenReady} pos=${p.positionMs} dur=${p.durationMs} err=${s.error?.code}")
        Thread.sleep(1500)
    }

    if (mode == "reparent") {
        println("[probe] REPARENT posBefore=$posBeforeReparent posAfter=$maxPos reloaded=${maxPos < posBeforeReparent}")
    }
    val played = maxPos > 0
    val harvested = !mode.startsWith("next") || harvests.get() > 0
    println("[probe] RESULT mode=$mode maxPos=$maxPos played=$played harvests=${harvests.get()}")
    SwingUtilities.invokeAndWait {
        player.release()
        frame?.dispose()
    }
    cef.dispose()
    exitProcess(if (played && harvested) 0 else 2)
}

/**
 * `next` mode only: reports, every 3s, everything the bundle's watch-next harvest depends on —
 * whether the movie player exists, whether it exposes `getWatchNextResponse`, what that returns,
 * and the microformat category the harvest gates on.
 */
private val NEXT_DIAG_JS = """
;(function(){
  var n = 0;
  setInterval(function(){
    try {
      var mp = document.getElementById('movie_player');
      if (!mp) { console.log('[diag] no movie_player'); return; }
      var pr = mp.getPlayerResponse ? mp.getPlayerResponse() : null;
      var hasFn = typeof mp.getWatchNextResponse;
      var wn = hasFn === 'function' ? mp.getWatchNextResponse() : undefined;
      var keys = wn ? Object.keys(wn).join('+') : String(wn);
      var cat = pr && pr.microformat && pr.microformat.playerMicroformatRenderer
        ? pr.microformat.playerMicroformatRenderer.category : '(none)';
      var es = wn && wn.playerOverlays && wn.playerOverlays.playerOverlayRenderer
        && wn.playerOverlays.playerOverlayRenderer.endScreen
        && wn.playerOverlays.playerOverlayRenderer.endScreen.watchNextEndScreenRenderer
        ? wn.playerOverlays.playerOverlayRenderer.endScreen.watchNextEndScreenRenderer.results.length : -1;
      var sec = wn && wn.contents && wn.contents.twoColumnWatchNextResults
        && wn.contents.twoColumnWatchNextResults.secondaryResults
        && wn.contents.twoColumnWatchNextResults.secondaryResults.secondaryResults
        ? wn.contents.twoColumnWatchNextResults.secondaryResults.secondaryResults.results.length : -1;
      console.log('[diag] #' + (++n) + ' vid=' + (pr && pr.videoDetails ? pr.videoDetails.videoId : '-')
        + ' getWatchNextResponse=' + hasFn + ' wn=' + keys + ' endScreen=' + es + ' secondary=' + sec
        + ' cat=' + cat + ' channel=' + (typeof window.ymixChannelAndroid));
    } catch (e) { console.log('[diag] error ' + e); }
  }, 3000);
})();
"""

/** The built webview-js bundle (same lookup as the shell). */
private fun readWebViewJsProbe(): String {
    val candidates = listOf("webview-js/dist/webview_ymusic.js", "../webview-js/dist/webview_ymusic.js")
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found (cwd=${System.getProperty("user.dir")}); run `npm run build` in webview-js/")
}

// A/B diagnostic (`nextdirect`): the pre-engine embed path — direct fetch + local rewrite, the same
// slot contract — to isolate whether a failure comes from the engine-served page or elsewhere.
private fun directProbeEmbedHtml(httpClient: KmpHttpClient): suspend (String) -> String = { userAgent ->
    val resp = httpClient.execute(
        kmphttp.RequestBuilder().header("User-Agent", userAgent).url("https://www.youtube.com/embed").build(),
    )
    val html = try {
        check(resp.isSuccessful) { "HTTP ${resp.code}" }
        resp.body.utf8String()
    } finally {
        resp.close()
    }
    val cspMeta = Regex("""(<meta[^>]*http-equiv=["'])content-security-policy(["'])""", RegexOption.IGNORE_CASE)
    val scriptPreload = Regex("""(<link\b[^>]*\bas=["'])script(["'])""", RegexOption.IGNORE_CASE)
    val rewritten = html
        .let { cspMeta.replace(it) { m -> "${m.groupValues[1]}content-security-policy-disabled${m.groupValues[2]}" } }
        .let { scriptPreload.replace(it) { m -> "${m.groupValues[1]}script-blocked${m.groupValues[2]}" } }
    val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE).find(rewritten)
    val at = head!!.range.last + 1
    rewritten.substring(0, at) + "<script>\nINJECT_JS_HERE\n</script>" + rewritten.substring(at)
}
