package ymusic.desktop

import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.runBlocking
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.JcefOsrSurface
import org.cef.handler.CefDisplayHandlerAdapter
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.embedPage
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.VideoQualityState
import ymusic.player.WebViewPlayer
import ymusic.player.WebViewPlayerContext
import ymusic.player.createWebViewPlayer
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
 * Run: `./gradlew :desktopApp:webviewProbe -PprobeArgs="realized"` (or `"headless"`). Muted (volume 0)
 * per the test-audio rule; muted also lets YouTube autoplay without a user gesture.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "realized"
    val realized = mode != "headless"
    val videoId = args.getOrNull(1) ?: "dQw4w9WgXcQ"
    println("[probe] mode=$mode realized=$realized videoId=$videoId")

    val httpClient: KmpHttpClient = KmpHttpClient.create {}
    // The embed page now comes from the scraping engine (yt/embed) — boot the baseline bundle.
    val engines = DynamicEngineManager(httpClient, appVersion = 1)
    runBlocking { engines.start() }
    val cef = CefManager()
    val cefClient = runBlocking { cef.createClient() }
    val webViewJs = readWebViewJsProbe() + if (mode == "next") NEXT_DIAG_JS else ""
    if (mode == "next") {
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
            embedHtml = { ua -> engines.embedPage(ua) },
        )
        // Muted (position advancing is the "is it playing" signal). `audioonly` exercises the
        // music-default path: audio-only must still play (the WebView stays shown on its thumbnail —
        // it disables the video track, not the surface).
        val quality = if (mode == "audioonly") VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO
        player.load(
            PlayableItem.YtVideoId(videoId),
            startPositionMs = 0L,
            playWhenReady = true,
            config = PlaybackConfig(preferVideoQuality = quality to 0L, volume = 0f),
        )
    }

    val harvests = java.util.concurrent.atomic.AtomicInteger()
    if (mode == "next") {
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
    while (System.currentTimeMillis() - start < 30_000) {
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
    val harvested = mode != "next" || harvests.get() > 0
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
