package io.ymusic.desktop

import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.yt.embedPage
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.VideoQualityState
import io.ymusic.player.WebViewPlayer
import io.ymusic.player.WebViewPlayerContext
import io.ymusic.player.createWebViewPlayer
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.ClientInfo
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import io.ymusic.ytmedia.sabr.SabrTrack
import io.ymusic.ytmedia.sabr.formatCacheKey
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.sink
import org.cef.browser.JcefOsrSurface
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * End-to-end SABR download probe. Wires the real desktop WebView player, plays a video so its
 * decoded player response + BotGuard PO token become available, extracts them via the new
 * [WebViewPlayer.requestPlayerResponse]/[WebViewPlayer.requestPoToken] commands, then downloads the
 * audio stream through :yt-media's [SabrSession] over an okhttp transport carrying the CEF cookies.
 *
 * Run: `./gradlew :desktopApp:sabrDownload -PsabrArgs="<videoId>"` (needs `webview-js/dist` built).
 * Muted per the test-audio rule. Verifies the downloaded file reaches the format's contentLength.
 */
fun main(args: Array<String>) {
    val videoId = args.getOrNull(0) ?: "dQw4w9WgXcQ"
    val outDir = File(System.getProperty("user.home"), ".ymusic/sabr-test").apply { mkdirs() }
    println("[sabr] videoId=$videoId out=$outDir")

    val logger = PrefixLogger("[engine]", kmpcommon.log.Logger.Level.WARN)
    val httpClient = kmphttp.KmpHttpClient.create {}
    val engines = DynamicEngineManager(httpClient, appVersion = 1, logger = logger)
    runBlocking { engines.start() }

    val cef = CefManager()
    val probeProfile = File(System.getProperty("user.home"), ".ymusic/jcef-probe-cache")
    val cefClient = runBlocking { cef.createClient(cacheDir = probeProfile) }
    val cookieManager = runBlocking { cef.awaitCookieManager() }
    val cefCookieJar = JcefCookieJar(cookieManager)
    val webViewJs = readWebViewJsForSabr()

    val surface = JcefOsrSurface()
    val container = JPanel().apply { layout = OverlayLayout(this) }
    var frame: JFrame? = null
    lateinit var player: WebViewPlayer

    SwingUtilities.invokeAndWait {
        // Realized (shown) window: the OSR browser only loads + plays when its peer is realized.
        frame = JFrame("SABR download probe").apply {
            contentPane.add(container)
            size = Dimension(480, 270)
            setLocationRelativeTo(null)
            isVisible = true
        }
        player = createWebViewPlayer(
            WebViewPlayerContext(cefClient, surface),
            engineJs = webViewJs,
            appVersion = 1,
            embedHtml = { ua -> engines.embedPage(ua) },
        )
        // Audio-only, muted — playback advancing is our "player response + botguard are ready" signal.
        player.load(
            PlayableItem.YtMedia(videoId),
            startPositionMs = 0L,
            playWhenReady = true,
            config = PlaybackConfig(preferVideoQuality = VideoQualityState.AUDIO_ONLY to 0L, volume = 0f),
        )
    }

    val exit = runCatching {
        runBlocking {
            awaitPlayback(player)
            println("[sabr] playback advancing — requesting player response + PO token")
            val dataJson = player.requestPlayerResponse(videoId)
            val poToken = player.requestPoToken(videoId)
            println("[sabr] got player response (${dataJson.length} chars) + PO token (${poToken.length} b64 chars)")

            val spec = buildSpec(videoId, dataJson, poToken)
            val audio = pickAudioFormat(spec.formats)
            println("[sabr] audio itag=${audio.itag} ${audio.mimeType} clen=${audio.clen} lmt=${audio.lmt}")

            val ext = if (audio.mimeType.contains("webm") || audio.mimeType.contains("opus")) "webm" else "m4a"
            val outFile = File(outDir, "$videoId.$ext")
            outFile.delete()
            // The app's standard client, carrying the CEF cookies and defaulting to no-redirect
            // (SABR signals redirects itself). JcefCookieJar is an okhttp3.CookieJar == kmphttp.CookieJar.
            val sabrClient = KmpHttpClient.create {
                cookieJar = cefCookieJar
                defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
            }
            // Route the download through a MediaCache so it mirrors segments into the player cache as it
            // goes — proving a download populates both the muxed file AND the cache (spans + index rows).
            val cacheDir = File(outDir, "player-cache").apply { mkdirs() }
            val cacheDriver = jvmSegmentDatabaseDriver()
            val blobs = DiskMediaBlobCache(cacheDir.absolutePath.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(cacheDriver, now = { System.currentTimeMillis() }))
            val index = SqlDelightSegmentIndex(cacheDriver, now = { System.currentTimeMillis() })
            val mediaCache = MediaCache(blobs, index)

            val startedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                SabrSession(logger = sabrProbeLogger { println("[sabr] $it") }).download(
                    client = sabrClient,
                    userAgent = CefManager.CHROME_USER_AGENT,
                    spec = spec,
                    audio = SabrTrack(audio, FileDownloadFile(outFile)),
                    progress = { done, total, _, _ ->
                        val pct = if (total > 0) done * 100 / total else 0
                        println("[sabr] audio $done / $total ($pct%)")
                    },
                    mediaCache = mediaCache,
                )
            }
            val secs = (System.currentTimeMillis() - startedAt) / 1000.0
            val fileOk = outFile.length() >= audio.clen

            // Cache verification: the whole stream should be cached, and reading it back must equal the file.
            val key = formatCacheKey(audio.itag, audio.lmt)
            val cachedWhole = blobs.isCached(key, 0, audio.clen)
            val cacheBytes = blobs.totalSize()
            val seg0 = index.findBySegmentIndex(audio.itag, audio.lmt, 0)
            val firstReal = index.findByStartTime(audio.itag, audio.lmt, 0)
            val readback = if (cachedWhole) {
                blobs.read(key, 0, audio.clen)?.buffer()?.use { it.readByteArray() }
            } else {
                null
            }
            val bytesMatch = readback != null && readback.size.toLong() == outFile.length() &&
                readback.toList() == outFile.readBytes().toList()
            println(
                "[sabr] CACHE key=$key cachedWhole=$cachedWhole cacheBytes=$cacheBytes " +
                    "initRow=${seg0 != null} firstSegRow=${firstReal != null} readbackMatchesFile=$bytesMatch",
            )
            val cacheOk = cachedWhole && cacheBytes >= audio.clen && seg0 != null && bytesMatch
            println("[sabr] RESULT file=${outFile.absolutePath} size=${outFile.length()} expected=${audio.clen} fileOk=$fileOk cacheOk=$cacheOk in ${secs}s")

            // Second download of the SAME track through the SAME cache — must be served entirely from the
            // cache (zero network rounds), proving download⇄cache reuse (plan §5, roadmap step 4).
            val reuseFile = File(outDir, "$videoId.reuse.$ext").apply { delete() }
            var networkRounds = 0
            var cacheServes = 0
            withContext(Dispatchers.IO) {
                SabrSession(
                    logger = sabrProbeLogger { line ->
                        if (line.startsWith("round rn=")) networkRounds++
                        if (line.startsWith("cache-serve")) cacheServes++
                        println("[sabr:reuse] $line")
                    },
                ).download(
                    client = sabrClient,
                    userAgent = CefManager.CHROME_USER_AGENT,
                    spec = spec,
                    audio = SabrTrack(audio, FileDownloadFile(reuseFile)),
                    mediaCache = mediaCache,
                )
            }
            val reuseOk = reuseFile.length() == outFile.length() &&
                reuseFile.readBytes().toList() == outFile.readBytes().toList() &&
                networkRounds == 0 && cacheServes > 0
            println("[sabr] REUSE size=${reuseFile.length()} networkRounds=$networkRounds cacheServes=$cacheServes reuseOk=$reuseOk")

            if (fileOk && cacheOk && reuseOk) 0 else 2
        }
    }.getOrElse { err ->
        System.err.println("[sabr] FAILED: ${err.message}")
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

/** Wait until the player reports real progress (position advancing), so the /player response is
 *  cached and BotGuard has run. Fails after 90s. */
private suspend fun awaitPlayback(player: WebViewPlayer) {
    val deadline = System.currentTimeMillis() + 90_000
    while (System.currentTimeMillis() < deadline) {
        val pos = player.progress.value.positionMs
        val state = player.status.value.state
        if (pos > 1_500) return
        val err = (state as? io.ymusic.player.PlaybackState.Error)?.error
        if (err != null && !err.canRetry) error("playback error ${err.code}: ${err.message}")
        withContext(Dispatchers.IO) { Thread.sleep(1000) }
    }
    error("playback did not advance within 90s")
}

private val json = Json { ignoreUnknownKeys = true }

private fun buildSpec(videoId: String, dataJson: String, poToken: String): SabrStreamSpec {
    val data = json.parseToJsonElement(dataJson).jsonObject
    val streaming = data["streamingData"]?.jsonObject ?: error("no streamingData")
    val sabrUrl = streaming["serverAbrStreamingUrl"]?.jsonPrimitive?.content
        ?: error("no serverAbrStreamingUrl (SABR-only stream required)")
    val ustreamer = data["videoPlaybackUstreamerConfig"]?.jsonPrimitive?.content
        ?: error("no videoPlaybackUstreamerConfig")
    val clientVersion = data["clientVersion"]?.jsonPrimitive?.content
    val clientName = data["clientNameId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
    val formats = (streaming["adaptiveFormats"] as? JsonArray).orEmpty().map { it.jsonObject.toAdaptiveFormat() }
    return SabrStreamSpec(
        videoId = videoId,
        sabrUrl = sabrUrl,
        ustreamerConfig = decodeBase64(ustreamer),
        poToken = decodeBase64(poToken),
        clientInfo = ClientInfo(clientName = clientName, clientVersion = clientVersion, osName = "Windows", osVersion = "10.0"),
        formats = formats,
    )
}

private fun JsonObject.toAdaptiveFormat(): AdaptiveFormat {
    val index = this["indexRange"]?.jsonObject
    return AdaptiveFormat(
        itag = long("itag").toInt(),
        lmt = long("lastModified"),
        xtag = str("xtags"),
        mimeType = str("mimeType") ?: "",
        clen = long("contentLength"),
        indexRangeStart = index?.let { it["start"]?.jsonPrimitive?.content?.toLongOrNull() } ?: 0,
        indexRangeEnd = index?.let { it["end"]?.jsonPrimitive?.content?.toLongOrNull() } ?: 0,
        width = longOrNull("width")?.toInt(),
        height = longOrNull("height")?.toInt(),
        approxDurationMs = long("approxDurationMs").toInt(),
        audioTrackIsDefault = (this["audioTrack"] as? JsonObject)?.get("audioIsDefault")?.jsonPrimitive?.content?.toBoolean(),
        audioTrackId = (this["audioTrack"] as? JsonObject)?.get("id")?.jsonPrimitive?.content,
        isDrc = this["isDrc"]?.jsonPrimitive?.content?.toBoolean() ?: false,
    )
}

/** Best audio: prefer webm/opus, then highest contentLength (proxy for bitrate). */
private fun pickAudioFormat(formats: List<AdaptiveFormat>): AdaptiveFormat {
    val audio = formats.filter { it.mimeType.startsWith("audio") && it.clen > 0 }
    require(audio.isNotEmpty()) { "no audio formats in player response" }
    return audio.sortedWith(
        compareByDescending<AdaptiveFormat> { it.mimeType.contains("webm") }.thenByDescending { it.clen },
    ).first()
}

/** okio-backed [DownloadFile] over a real file. One append sink open at a time (SabrSession closes
 *  each segment's sink before opening the next), so [length] is accurate between segments. */
private class FileDownloadFile(private val file: File) : DownloadFile {
    override fun length(): Long = if (file.exists()) file.length() else 0
    override fun appendSink() = java.io.FileOutputStream(file, true).sink().buffer()
    override fun close() {}
}

private fun decodeBase64(s: String): ByteArray = runCatching {
    java.util.Base64.getDecoder().decode(s)
}.getOrElse {
    java.util.Base64.getUrlDecoder().decode(s.trim())
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLongOrNull()
private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** The built webview-js bundle (same lookup as the shell/probe). */
private fun readWebViewJsForSabr(): String {
    val candidates = listOf("webview-js/dist/webview_ymusic.js", "../webview-js/dist/webview_ymusic.js")
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found; run `npm run build` in webview-js/")
}

/** Probe-only logger: every line (any level) to [onLine]; forks collapse onto itself. */
private fun sabrProbeLogger(onLine: (String) -> Unit): kmpcommon.log.Logger = object : kmpcommon.log.Logger {
    override val trail: List<kmpcommon.log.Crumb> get() = emptyList()

    override fun fork(tag: String, prop: Map<String, Any>?): kmpcommon.log.Logger = this

    override fun forkAll(crumbs: List<kmpcommon.log.Crumb>): kmpcommon.log.Logger = this

    override fun log(level: kmpcommon.log.Logger.Level, message: String, error: Throwable?) = onLine(message)
}
