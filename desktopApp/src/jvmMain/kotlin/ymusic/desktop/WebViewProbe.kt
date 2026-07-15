package ymusic.desktop

import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.runBlocking
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
 * Run: `./gradlew :desktopApp:webviewProbe -PprobeArgs="realized"` (or `"headless"`). Muted (volume 0)
 * per the test-audio rule; muted also lets YouTube autoplay without a user gesture.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "realized"
    val realized = mode != "headless"
    val videoId = args.getOrNull(1) ?: "dQw4w9WgXcQ"
    println("[probe] mode=$mode realized=$realized videoId=$videoId")

    val httpClient: KmpHttpClient = KmpHttpClient.create {}
    val cef = CefManager()
    val cefClient = runBlocking { cef.createClient() }
    val webViewJs = readWebViewJsProbe()

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
            WebViewPlayerContext(cefClient, container),
            engineJs = webViewJs,
            appVersion = 1,
            httpClient = httpClient,
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
    println("[probe] RESULT mode=$mode maxPos=$maxPos played=$played")
    SwingUtilities.invokeAndWait {
        player.release()
        frame?.dispose()
    }
    cef.dispose()
    exitProcess(if (played) 0 else 2)
}

/** The built webview-js bundle (same lookup as the shell). */
private fun readWebViewJsProbe(): String {
    val candidates = listOf("webview-js/dist/webview_ymusic.js", "../webview-js/dist/webview_ymusic.js")
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found (cwd=${System.getProperty("user.dir")}); run `npm run build` in webview-js/")
}
