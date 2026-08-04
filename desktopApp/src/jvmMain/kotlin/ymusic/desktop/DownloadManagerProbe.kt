package ymusic.desktop

import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.cef.browser.JcefOsrSurface
import ymusic.download.DownloadFormat
import ymusic.download.DownloadRequest
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadStore
import ymusic.download.Ffmpeg
import ymusic.download.downloadDatabaseJdbcDriver
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.embedPage
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.WebViewPlayer
import ymusic.player.WebViewPlayerContext
import ymusic.player.createWebViewPlayer
import ymusic.ui.WebViewSabrSpecResolver
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * End-to-end Download Manager probe. Wires the real desktop WebView player as a [WebViewSabrSpecResolver],
 * builds a real [DownloadService] over the prebuilt ffmpeg, then enqueues an MP3 and an M4A download of a
 * video and waits for both to reach [DownloadState.COMPLETED], verifying the output files exist and are
 * non-empty. Proves the whole new stack: queue → SABR download → ffmpeg transcode → final file.
 *
 * Run: `./gradlew :desktopApp:downloadManager -PsabrArgs="<videoId>"` (needs `webview-js/dist` built).
 * Muted per the test-audio rule.
 */
fun main(args: Array<String>) {
    val videoId = args.getOrNull(0) ?: "dQw4w9WgXcQ"
    val outDir = File(System.getProperty("user.home"), ".ymusic/download-test").apply { mkdirs() }
    val ffmpegBin = locateFfmpeg() ?: run {
        System.err.println("[dl] no prebuilt ffmpeg for this host — cannot verify transcode")
        exitProcess(4)
    }
    println("[dl] videoId=$videoId out=$outDir ffmpeg=$ffmpegBin")

    val logger = object : kmpcommon.log.Logger {
        override fun log(level: kmpcommon.log.Logger.Level, tags: List<String>, message: String, error: Throwable?) {
            println("[dl] $level ${tags.joinToString("/")}: $message" + (error?.let { " (${it.message})" } ?: ""))
        }
    }
    val engines = DynamicEngineManager(KmpHttpClient.create {}, appVersion = 1, logger = logger)
    runBlocking { engines.start() }

    val cef = CefManager()
    val cefClient = runBlocking { cef.createClient(cacheDir = File(System.getProperty("user.home"), ".ymusic/jcef-probe-cache")) }
    val cookieManager = runBlocking { cef.awaitCookieManager() }
    val cefCookieJar = JcefCookieJar(cookieManager)
    val webViewJs = readWebViewJsForDownload()

    val surface = JcefOsrSurface()
    val container = JPanel().apply { layout = OverlayLayout(this) }
    var frame: JFrame? = null
    lateinit var player: WebViewPlayer
    SwingUtilities.invokeAndWait {
        frame = JFrame("Download Manager probe").apply {
            contentPane.add(container)
            size = Dimension(480, 270)
            setLocationRelativeTo(null)
            isVisible = true // the OSR browser only loads + plays when its peer is realized
        }
        player = createWebViewPlayer(
            WebViewPlayerContext(cefClient, surface),
            engineJs = webViewJs,
            appVersion = 5000,
            embedHtml = { ua -> engines.embedPage(ua) },
        )
        // Warm the page so the first resolve doesn't wait on a cold WebView (audio-only, muted).
        player.load(PlayableItem.YtVideoId(videoId), config = PlaybackConfig(volume = 0f))
    }

    val exit = runCatching {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sabrClient = KmpHttpClient.create {
                cookieJar = cefCookieJar
                defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
            }
            val service = DownloadService(
                store = DownloadStore(downloadDatabaseJdbcDriver("jdbc:sqlite:${File(outDir, "downloads.db").absolutePath}")),
                scope = scope,
                now = { System.currentTimeMillis() },
                resolver = WebViewSabrSpecResolver(player),
                client = sabrClient,
                userAgent = CefManager.CHROME_USER_AGENT,
                ffmpeg = Ffmpeg(ffmpegBin.absolutePath),
                storage = ymusic.download.OkioDownloadStorage(
                    FileSystem.SYSTEM,
                    File(outDir, "work").absolutePath.toPath(),
                    File(outDir, "out").absolutePath.toPath(),
                ),
                logger = logger,
            )

            val mp3 = service.enqueue(DownloadRequest(videoId, "Probe $videoId", artist = "Test", format = DownloadFormat.MP3))
            val m4a = service.enqueue(DownloadRequest(videoId, "Probe $videoId", artist = "Test", format = DownloadFormat.M4A))
            val wanted = setOf(mp3, m4a)

            // Wait up to 5 min for both tasks to finish (COMPLETED or FAILED).
            val deadline = System.currentTimeMillis() + 300_000
            while (System.currentTimeMillis() < deadline) {
                val tasks = service.tasks.value.filter { it.id in wanted }
                tasks.forEach { println("[dl]   ${it.format} ${it.state} ${it.downloadedBytes}/${it.totalBytes}") }
                if (tasks.size == 2 && tasks.all { it.state.isTerminal || it.state == DownloadState.FAILED }) break
                delay(2000)
            }

            val results = service.tasks.value.filter { it.id in wanted }
            var ok = true
            results.forEach { t ->
                val file = t.fileUri?.let { File(it) }
                val good = t.state == DownloadState.COMPLETED && file != null && file.exists() && file.length() > 0
                println("[dl] RESULT ${t.format} state=${t.state} file=${file?.absolutePath} size=${file?.length()} ${if (good) "OK" else "FAIL: ${t.errorMessage ?: ""}"}")
                ok = ok && good
            }
            if (ok && results.size == 2) 0 else 2
        }
    }.getOrElse { err ->
        System.err.println("[dl] FAILED: ${err.message}")
        err.printStackTrace()
        3
    }

    SwingUtilities.invokeAndWait {
        player.release()
        frame?.dispose()
    }
    cef.dispose()
    exitProcess(exit)
}

private fun locateFfmpeg(): File? {
    System.getProperty("ymusic.ffmpeg")?.let { return File(it).takeIf { f -> f.exists() } }
    val os = System.getProperty("os.name").lowercase()
    val rel = when {
        os.contains("mac") -> "ffmpeg-prebuilt-ymusic/macos/ffmpeg"
        os.contains("linux") -> "ffmpeg-prebuilt-ymusic/linux-x86_64/ffmpeg"
        else -> return null
    }
    return listOf(rel, "../$rel").map(::File).firstOrNull { it.exists() }
}

private fun readWebViewJsForDownload(): String {
    val candidates = listOf("webview-js/dist/webview_ymusic.js", "../webview-js/dist/webview_ymusic.js")
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found; run `npm run build` in webview-js/")
}
