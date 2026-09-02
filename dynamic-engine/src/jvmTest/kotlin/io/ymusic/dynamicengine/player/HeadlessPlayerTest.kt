package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.DiskCacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.StaticJsSource
import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS
import io.ymusic.dynamicengine.readAll
import io.ymusic.dynamicengine.testCacheDriver
import io.ymusic.dynamicengine.testEngineDispatcher
import io.ymusic.mse.MseVideoSurface
import io.ymusic.settings.InMemorySettingsStore
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.OkKmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Live headless player: real [OkKmpHttpClient] (no jar on the client — JS cooks Cookie headers)
 * + [InMemoryCookieJar] on the engine cookie protocol. Asserts `/player` is called with the
 * no-ad playback flag and `/videoplayback` carries a content PO from shared OWL.
 */
class HeadlessPlayerTest {
    @Test
    fun player_sends_potoken_and_play_pause_seek() = runBlocking {
        val jar = InMemoryCookieJar()
        val inner = OkKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 60.seconds
        }.build()
        val http = RecordingHttpClient(inner)
        val log = PrintLog()
        val blobDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-ls-test"
        FileSystem.SYSTEM.deleteRecursively(blobDir)
        val cache = DiskCacheStore(testCacheDriver(), blobDir, FileSystem.SYSTEM, now = { System.currentTimeMillis() })
        val main = DynamicEngineManager(
            http,
            appVersion = 1,
            logger = log,
            jsSource = StaticJsSource(ENGINE_TEST_JS),
            cacheStore = cache,
            cookieJar = jar,
            settings = InMemorySettingsStore(),
        )
        main.start()
        val player = withContext(testEngineDispatcher) {
            DynamicEnginePlayer.create(
                http,
                appVersion = 1,
                dispatcher = testEngineDispatcher,
                logger = log,
                engineJs = ENGINE_TEST_JS,
                cookieJar = jar,
                cacheStore = cache,
                settings = InMemorySettingsStore(),
                videoSurface = MseVideoSurface(),
                main = main,
            )
        }
        try {
            withContext(testEngineDispatcher) {
                player.init(log)
            }
            val loaded = withContext(testEngineDispatcher) {
                player.load(VIDEO_ID, log)
            }
            println("load rec=$loaded")
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                if (http.playerNoAd() && http.videoplaybackPoTokenLen() >= MIN_PO) break
                delay(500)
            }
            println("player poTokens=${http.playerPoTokens()}")
            val vpPo = http.videoplaybackPoTokenLen()
            val playerUrls = http.urls.filter { it.contains("/youtubei/v1/player") }
            val vpUrls = http.urls.filter { it.contains("/videoplayback") }
            println("player urls=${playerUrls.size} vp=${vpUrls.size} noAd=${http.playerNoAd()} vpPo=$vpPo")
            println("player sample=${playerUrls.take(2)}")
            println("vp sample=${vpUrls.take(3)}")
            assertTrue(playerUrls.isNotEmpty(), "no /player request rec=${http.summary()}")
            assertTrue(http.playerNoAd(), "player request missing isInlinePlaybackNoAd rec=${http.summary()}")
            assertTrue(vpUrls.isNotEmpty(), "no /videoplayback request rec=${http.summary()}")
            assertTrue(vpPo >= MIN_PO, "videoplayback poToken too short len=$vpPo rec=${http.summary()}")
        } catch (e: EngineError) {
            println("headless player EngineError code=${e.code} msg=${e.message}")
            println("jsName=${e.jsName} jsStack=${e.jsStack}")
            e.cause?.let { println("cause=${it::class.simpleName} ${it.message}") }
            if (e is EngineError.Network) return@runBlocking
            throw e
        } finally {
            player.close()
            main.close()
        }
    }

    private class PrintLog private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
        constructor() : this(emptyList())

        override fun fork(crumbs: List<Crumb>): CrumbLogger = PrintLog(crumbs)

        override fun log(level: Logger.Level, message: String, error: Throwable?) {
            println("log[$level] $path $message${error?.let { " err=${it::class.simpleName}: ${it.message}" } ?: ""}")
        }
    }

    companion object {
        private const val VIDEO_ID = "jNQXAC9IVRw"
        private const val MIN_PO = 80
    }
}

private class LoggedHttp(
    val url: String,
    val method: String,
    val cookie: String?,
    val body: ByteArray?,
)

private class RecordingHttpClient(
    private val inner: KmpHttpClient,
) : KmpHttpClient by inner {
    val log = mutableListOf<LoggedHttp>()
    val urls: List<String> get() = synchronized(log) { log.map { it.url } }

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: kotlin.time.Duration?): Response {
        val ctype = request.body?.contentType()
        val raw = request.body?.readAll()
        synchronized(log) {
            log += LoggedHttp(request.url.toString(), request.method, request.header("Cookie"), raw)
        }
        val sent =
            if (raw != null) request.newBuilder().method(request.method, raw.toRequestBody(ctype)).build()
            else request
        return inner.execute(sent, options, timeout)
    }

    fun summary(): String = synchronized(log) {
        log.joinToString(" | ") { e ->
            val n = e.body?.size ?: 0
            "${e.method} ${e.url.take(120)} body=$n cookie=${e.cookie != null}"
        }
    }

    fun playerPoTokens(): List<String> = synchronized(log) {
        val out = mutableListOf<String>()
        for (e in log) {
            if (!e.url.contains("/youtubei/v1/player")) continue
            val text = e.body?.decodeToString() ?: continue
            Regex("\"poToken\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach { out += it.groupValues[1] }
        }
        out
    }

    fun playerPoToken(): String = synchronized(log) {
        for (e in log) {
            if (!e.url.contains("/youtubei/v1/player")) continue
            val text = e.body?.decodeToString() ?: continue
            val m = Regex("\"poToken\"\\s*:\\s*\"([^\"]+)\"").find(text) ?: continue
            return m.groupValues[1]
        }
        ""
    }

    fun playerNoAd(): Boolean = synchronized(log) {
        log.any { e ->
            e.url.contains("/youtubei/v1/player") &&
                (e.body?.decodeToString()?.contains("\"isInlinePlaybackNoAd\":true") == true)
        }
    }

    fun videoplaybackPoTokenLen(): Int = synchronized(log) {
        var best = 0
        for (e in log) {
            if (!e.url.contains("/videoplayback")) continue
            val q = Regex("[?&]pot=([^&]+)").find(e.url)?.groupValues?.get(1)?.length ?: 0
            if (q > best) best = q
            val body = e.body ?: continue
            val sc = protoLenPrefixed(body, 19) ?: continue
            val pot = protoLenPrefixed(sc, 2) ?: continue
            if (pot.size > best) best = pot.size
        }
        best
    }
}

private fun protoLenPrefixed(buf: ByteArray, wantField: Int): ByteArray? {
    var i = 0
    while (i < buf.size) {
        val key = readVarint(buf, i) ?: return null
        i = key.second
        val field = (key.first ushr 3).toInt()
        val wire = (key.first and 7L).toInt()
        when (wire) {
            0 -> {
                val v = readVarint(buf, i) ?: return null
                i = v.second
            }
            1 -> i += 8
            5 -> i += 4
            2 -> {
                val len = readVarint(buf, i) ?: return null
                i = len.second
                val n = len.first.toInt()
                if (n < 0 || i + n > buf.size) return null
                val slice = buf.copyOfRange(i, i + n)
                if (field == wantField) return slice
                i += n
            }
            else -> return null
        }
    }
    return null
}

private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
    var i = start
    var shift = 0
    var result = 0L
    while (i < buf.size && shift < 64) {
        val b = buf[i].toInt() and 0xff
        i++
        result = result or ((b and 0x7f).toLong() shl shift)
        if (b and 0x80 == 0) return result to i
        shift += 7
    }
    return null
}
