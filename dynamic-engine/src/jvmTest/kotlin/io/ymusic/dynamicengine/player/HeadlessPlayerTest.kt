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
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.readUmpPartHeader
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.ResponseBody
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.buffer
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

    @Test
    fun player_audio_only_advances() = runBlocking {
        val jar = InMemoryCookieJar()
        val inner = OkKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 60.seconds
        }.build()
        val mediaDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-media-cache"
        FileSystem.SYSTEM.deleteRecursively(mediaDir)
        val driver = jvmSegmentDatabaseDriver()
        val mediaCache = MediaCache(
            DiskMediaBlobCache(mediaDir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
        )
        val proxy = LocalMediaProxy(
            mediaCache,
            inner,
            userAgent = DESKTOP_UA,
        )
        val http = RecordingHttpClient(SabrProxyHttpClient(inner, proxy))
        val log = PrintLog()
        val blobDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-ao-test"
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
            withContext(testEngineDispatcher) {
                player.load(AUDIO_ONLY_VIDEO_ID, log, audioOnly = true)
                player.play(log)
            }
            var lastBuffered = 0L
            var lastPos = 0L
            var lastMoveAt = System.currentTimeMillis()
            val samples = mutableListOf<Pair<Long, Long>>()
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                val st = withContext(testEngineDispatcher) { player.status(log) }
                val pos = st.longField("positionMs")
                lastBuffered = st.longField("bufferedMs")
                if (pos > lastPos + 200) lastMoveAt = System.currentTimeMillis()
                lastPos = pos
                samples += pos to lastBuffered
                if (pos >= NEED_POS_MS && lastBuffered > pos) break
                if (pos in 500 until NEED_POS_MS && System.currentTimeMillis() - lastMoveAt > 5_000) {
                    throw AssertionError(
                        "clock frozen at ${pos}ms buffered=$lastBuffered rec=${http.summary()}",
                    )
                }
                delay(500)
            }
            val selV = http.lastSabr()?.selectedVideoFormats?.map { it.itag }.orEmpty()
            println(
                "ao pos=$lastPos buffered=$lastBuffered selV=$selV " +
                    "samples=$samples rec=${http.summary()}",
            )
            assertTrue(http.urls.any { it.contains("/videoplayback") && it.contains("sabr=1") }, "no SABR rec=${http.summary()}")
            assertTrue(lastPos >= NEED_POS_MS, "clock did not reach ${NEED_POS_MS}ms pos=$lastPos buffered=$lastBuffered")
            assertTrue(lastBuffered > lastPos, "leftover not ahead of clock pos=$lastPos buffered=$lastBuffered")
        } catch (e: EngineError) {
            println("headless ao EngineError code=${e.code} msg=${e.message}")
            println("jsName=${e.jsName} jsStack=${e.jsStack}")
            e.cause?.let { println("cause=${it::class.simpleName} ${it.message}") }
            if (e is EngineError.Network) return@runBlocking
            throw e
        } finally {
            player.close()
            main.close()
        }
    }

    @Test
    fun player_set_speed_advances_faster() = runBlocking {
        val jar = InMemoryCookieJar()
        val inner = OkKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 60.seconds
        }.build()
        val mediaDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-speed-cache"
        FileSystem.SYSTEM.deleteRecursively(mediaDir)
        val driver = jvmSegmentDatabaseDriver()
        val mediaCache = MediaCache(
            DiskMediaBlobCache(mediaDir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
        )
        val proxy = LocalMediaProxy(
            mediaCache,
            inner,
            userAgent = DESKTOP_UA,
        )
        val http = RecordingHttpClient(SabrProxyHttpClient(inner, proxy))
        val log = PrintLog()
        val blobDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-speed-engine"
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
                player.load(AUDIO_ONLY_VIDEO_ID, log, audioOnly = true)
                player.play(log)
            }
            val readyBy = System.currentTimeMillis() + 60_000
            var pos = 0L
            while (System.currentTimeMillis() < readyBy) {
                pos = withContext(testEngineDispatcher) { player.status(log) }.longField("positionMs")
                if (pos >= 2_000L) break
                delay(400)
            }
            assertTrue(pos >= 2_000L, "clock did not start pos=$pos rec=${http.summary()}")
            suspend fun mediaPerWall(windowMs: Long): Double {
                val a = withContext(testEngineDispatcher) { player.status(log) }.longField("positionMs")
                delay(windowMs)
                val b = withContext(testEngineDispatcher) { player.status(log) }.longField("positionMs")
                return (b - a).toDouble() / windowMs.toDouble()
            }
            val atOne = mediaPerWall(2_500)
            withContext(testEngineDispatcher) { player.setSpeed(2f, logger = log) }
            delay(400)
            val atTwo = mediaPerWall(2_500)
            println("speed at1=$atOne at2=$atTwo pos=$pos")
            assertTrue(atTwo > atOne * 1.4, "2× should outrun 1× at1=$atOne at2=$atTwo")
            assertTrue(atTwo > 1.3, "2× media/wall should be well above 1 at2=$atTwo")
        } catch (e: EngineError) {
            println("headless speed EngineError code=${e.code} msg=${e.message}")
            println("jsName=${e.jsName} jsStack=${e.jsStack}")
            e.cause?.let { println("cause=${it::class.simpleName} ${it.message}") }
            if (e is EngineError.Network) return@runBlocking
            throw e
        } finally {
            player.close()
            main.close()
        }
    }

    @Test
    fun player_live_audio_only_advances_past_leftover_drain() = runBlocking {
        val jar = InMemoryCookieJar()
        val inner = OkKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 60.seconds
        }.build()
        val mediaDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-live-ao-cache"
        FileSystem.SYSTEM.deleteRecursively(mediaDir)
        val driver = jvmSegmentDatabaseDriver()
        val mediaCache = MediaCache(
            DiskMediaBlobCache(mediaDir, FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
        )
        val log = PrintLog()
        val proxy = LocalMediaProxy(
            mediaCache,
            inner,
            userAgent = DESKTOP_UA,
            logger = log,
            verbose = true,
        )
        val http = RecordingHttpClient(SabrProxyHttpClient(inner, proxy))
        val blobDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-player-live-ao-engine"
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
            withContext(testEngineDispatcher) {
                player.load(LIVE_VIDEO_ID, log, quality = "auto")
                player.play(log)
            }
            var lastPos = 0L
            var lastBuffered = 0L
            var lastMoveAt = System.currentTimeMillis()
            var posAtZero = 0L
            var leadAtZero = 8_000L
            var switched = false
            val startWait = System.currentTimeMillis()
            while (System.currentTimeMillis() - startWait < 25_000) {
                val st = withContext(testEngineDispatcher) { player.status(log) }
                lastPos = st.longField("positionMs")
                lastBuffered = st.longField("bufferedMs")
                println("live-ao wait pos=$lastPos buffered=$lastBuffered live=${st.flag("live")} dur=${st.longField("durationMs")}")
                if (lastPos > 1_000) break
                delay(500)
            }
            run {
                val st = withContext(testEngineDispatcher) { player.status(log) }
                lastPos = st.longField("positionMs")
                lastBuffered = st.longField("bufferedMs")
                leadAtZero = (lastBuffered - lastPos).coerceIn(3_000L, 45_000L)
                posAtZero = lastPos
                withContext(testEngineDispatcher) { player.setQuality("auto", true, log) }
                switched = true
                lastMoveAt = System.currentTimeMillis()
                println("live-ao switch pos=$posAtZero lead=$leadAtZero buffered=$lastBuffered live=${st.flag("live")}")
            }
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                val st = withContext(testEngineDispatcher) { player.status(log) }
                val pos = st.longField("positionMs")
                lastBuffered = st.longField("bufferedMs")
                val audioOnly = st.flag("audioOnly")
                if (pos > lastPos + 200) lastMoveAt = System.currentTimeMillis()
                lastPos = pos
                val need = posAtZero + leadAtZero + NEED_POS_MS
                if (audioOnly && pos >= need) break
                val leftoverGone = pos >= posAtZero + leadAtZero - 2_000
                if (leftoverGone && System.currentTimeMillis() - lastMoveAt > 8_000) {
                    val selV = http.lastSabr()?.selectedVideoFormats?.map { it.itag }.orEmpty()
                    throw AssertionError(
                        "live clock frozen after leftover drain pos=$pos buffered=$lastBuffered " +
                            "posAtZero=$posAtZero lead=$leadAtZero selV=$selV " +
                            "audioOnly=$audioOnly rec=${http.summary()}",
                    )
                }
                delay(500)
            }
            val selV = http.lastSabr()?.selectedVideoFormats?.map { it.itag }.orEmpty()
            val st = withContext(testEngineDispatcher) { player.status(log) }
            println(
                "live-ao pos=$lastPos buffered=$lastBuffered selV=$selV " +
                    "posAtZero=$posAtZero lead=$leadAtZero audioOnly=${st.flag("audioOnly")} " +
                    "quality=${st["quality"]} rec=${http.summary()}",
            )
            assertTrue(switched, "never reached auto leftover to switch zero pos=$lastPos buffered=$lastBuffered live=${st.flag("live")}")
            assertTrue(st.flag("audioOnly"), "not audio-only after zero")
            assertTrue(
                lastPos >= posAtZero + leadAtZero + NEED_POS_MS,
                "clock did not pass leftover drain pos=$lastPos need=${posAtZero + leadAtZero + NEED_POS_MS} selV=$selV",
            )
        } catch (e: EngineError) {
            println("headless live-ao EngineError code=${e.code} msg=${e.message}")
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
        /** Longer public VOD — jNQXAC9IVRw is ~19s and hid a 3s stall. */
        private const val AUDIO_ONLY_VIDEO_ID = "x72VDoEpTIU"
        /** 24/7 live (N0vxXktZqXQ is no longer live). Override with MSE_VIDEO_ID. */
        private val LIVE_VIDEO_ID =
            System.getenv("MSE_VIDEO_ID")?.takeIf { it.isNotBlank() } ?: "GotlA1KKWoo"
        private const val MIN_PO = 80
        private const val NEED_POS_MS = 8_000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
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
    private val sabrUmp = mutableListOf<Buffer>()

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: kotlin.time.Duration?): Response {
        val ctype = request.body?.contentType()
        val raw = request.body?.readAll()
        synchronized(log) {
            log += LoggedHttp(request.url.toString(), request.method, request.header("Cookie"), raw)
        }
        val sent =
            if (raw != null) request.newBuilder().method(request.method, raw.toRequestBody(ctype)).build()
            else request
        val resp = inner.execute(sent, options, timeout)
        val url = sent.url.toString()
        if (sent.method.equals("POST", ignoreCase = true) && url.contains("sabr=1")) {
            return teeSabr(resp)
        }
        return resp
    }

    private fun teeSabr(resp: Response): Response {
        val copy = Buffer()
        synchronized(sabrUmp) { sabrUmp += copy }
        val origin = resp.body
        val teed = object : ResponseBody() {
            override fun contentType() = origin.contentType()
            override fun contentLength() = origin.contentLength()
            override fun source() = object : ForwardingSource(origin.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        val dup = sink.peek()
                        val skip = sink.size - n
                        if (skip > 0) dup.skip(skip)
                        copy.write(dup, n)
                    }
                    return n
                }
            }.buffer()
            override fun close() = origin.close()
        }
        return resp.newBuilder().body(teed).build()
    }

    fun sabrUmpHasItag(itag: Int): Boolean = synchronized(sabrUmp) {
        sabrUmp.any { containsMediaItag(it.peek().readByteArray(), itag) }
    }

    fun lastSabr(): VideoPlaybackAbrRequest? = synchronized(log) {
        log.asReversed().firstNotNullOfOrNull { e ->
            if (!e.url.contains("sabr=1") || e.body == null) null
            else runCatching { VideoPlaybackAbrRequest.from(ProtoReader(e.body).readAllFields()) }.getOrNull()
        }
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

private fun containsMediaItag(ump: ByteArray, itag: Int): Boolean {
    val buf = Buffer().write(ump)
    while (!buf.exhausted()) {
        val h = runCatching { buf.readUmpPartHeader() }.getOrNull() ?: return false
        if (h.partId == UmpPart.MEDIA_HEADER) {
            val mh = runCatching {
                MediaHeader.from(ProtoReader(buf.readByteArray(h.size)).readAllFields())
            }.getOrNull()
            if (mh?.itag == itag) return true
        } else {
            if (h.size > buf.size) return false
            buf.skip(h.size)
        }
    }
    return false
}

private fun JsonObject.flag(key: String): Boolean {
    val p = this[key] as? JsonPrimitive ?: return false
    return p.booleanOrNull == true || p.content == "true"
}
