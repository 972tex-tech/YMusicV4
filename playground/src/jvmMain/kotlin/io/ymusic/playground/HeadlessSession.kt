package io.ymusic.playground

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.desktopEngineCacheStore
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.InMemorySettingsStore
import io.ymusic.settings.SettingsStore
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.OkKmpHttpClient
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Reassemble `bg dump` console chunks into one timestamped session dir. */
internal class BgCapture(private val parentDir: File) {
    private val parts = HashMap<String, Array<String?>>()
    private var metaJson = ""
    private var dumpDir: File? = null
    private val millerReady = AtomicBoolean(false)
    private val sabrReady = AtomicBoolean(false)

    @Synchronized
    fun dir(): File {
        dumpDir?.let { return it }
        val stamp = field("sessionKey").ifEmpty { utcStamp() }
        val created = File(parentDir, stamp)
        created.mkdirs()
        dumpDir = created
        pointLatest(parentDir, stamp)
        println("log[DEBUG] bg-dump dir=${created.absolutePath}")
        return created
    }

    @Synchronized
    fun ingest(message: String) {
        if (!message.startsWith("bg dump ")) return
        val rest = message.substring("bg dump ".length)
        when {
            rest.startsWith("meta ") -> {
                metaJson = rest.substring("meta ".length).trim()
                File(dir(), "meta.json").writeText(metaJson + "\n")
                File(dir(), "console.txt").appendText(message + "\n")
                maybeMillerReady()
            }
            rest.startsWith("sabr meta ") ->
                File(dir(), "console.txt").appendText(message + "\n")
            rest.startsWith("entropy meta ") ->
                File(dir(), "console.txt").appendText(message + "\n")
            rest.startsWith("timeOrigin ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaValue("timeOrigin", rest.substring("timeOrigin ".length).trim())
            }
            rest.startsWith("eventId ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("eventId", rest.substring("eventId ".length).trim())
            }
            rest.startsWith("interpUrl ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("interpUrl", rest.substring("interpUrl ".length).trim())
            }
            rest.startsWith("mintId ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("mintId", rest.substring("mintId ".length).trim())
            }
            rest.startsWith("sessionKey ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("sessionKey", rest.substring("sessionKey ".length).trim())
            }
            rest.startsWith("globalName ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("globalName", rest.substring("globalName ".length).trim())
            }
            rest.startsWith("ua ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                putMetaString("ua", rest.substring("ua ".length).trim())
            }
            rest.startsWith("sps ") -> {
                File(dir(), "console.txt").appendText(message + "\n")
                val sps = rest.substring("sps ".length).trim()
                putMetaValue("sps", sps)
                println("log[DEBUG] bg capture sps $sps dir=${dir().absolutePath}")
            }
            else -> {
                File(dir(), "console.txt").appendText(message + "\n")
                val sp = rest.indexOf(' ')
                if (sp < 0) return
                val kind = rest.substring(0, sp)
                val tail = rest.substring(sp + 1)
                val sp2 = tail.indexOf(' ')
                if (sp2 < 0) return
                val frac = tail.substring(0, sp2)
                val data = tail.substring(sp2 + 1)
                val slash = frac.indexOf('/')
                if (slash < 0) return
                val i = frac.substring(0, slash).toIntOrNull() ?: return
                val n = frac.substring(slash + 1).toIntOrNull() ?: return
                if (n <= 0 || i < 0 || i >= n) return
                val arr = parts.getOrPut(kind) { arrayOfNulls(n) }
                val slot = if (arr.size == n) arr else arrayOfNulls<String>(n).also { parts[kind] = it }
                slot[i] = data
                if (slot.all { it != null }) {
                    val joined = decodeDump(slot.joinToString("") { it!! })
                    writeKind(kind, joined)
                    maybeMillerReady()
                    maybeSabrReady()
                }
            }
        }
    }

    private fun decodeDump(value: String): String {
        if (!value.startsWith("b64:")) return value
        val raw = value.substring(4)
        val decoder = java.util.Base64.getUrlDecoder()
        return try {
            String(decoder.decode(raw), Charsets.UTF_8)
        } catch (_: Exception) {
            value
        }
    }

    private fun writeKind(kind: String, value: String) {
        val name = when (kind) {
            "program" -> "program.txt"
            "snapshot" -> "snapshot.txt"
            "integrityToken" -> "integrityToken.txt"
            "visitorData" -> "visitorData.txt"
            "pot" -> "pot.txt"
            "sabrUrl" -> "sabr_url.txt"
            "sabrBody" -> "sabr_body.b64url"
            "interpJs" -> "interpreter.js"
            "entropy" -> "entropy.txt"
            else -> return
        }
        File(dir(), name).writeText(value)
        if (kind == "program") writeInject(value)
    }

    private fun writeInject(program: String) {
        val globalName = field("globalName").ifEmpty { "trayride" }
        val interpUrl = field("interpUrl")
        val hash = interpUrl.substringAfterLast('/').removeSuffix(".js")
        val payload =
            "{\"globalName\":${jsonStr(globalName)},\"interpreterHash\":${jsonStr(hash)}," +
                "\"program\":${jsonStr(program)},\"requestKey\":\"O43z0dpjhgX20SCx4KAo\"," +
                "\"source\":\"playground-bg-dump\"}"
        File(dir(), "challenge_inject.js").writeText("globalThis.__BBD_BG = $payload;\n")
    }

    private fun field(key: String): String {
        val m = Regex("\"$key\":\"([^\"]*)\"").find(metaJson) ?: return ""
        return m.groupValues[1]
    }

    private fun putMetaString(key: String, value: String) {
        putMetaValue(key, jsonStr(value))
    }

    private fun putMetaValue(key: String, jsonValue: String) {
        if (jsonValue.isEmpty()) return
        val existing = Regex("\"$key\":(\"[^\"]*\"|-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)")
        metaJson = if (metaJson.isBlank()) {
            "{\"$key\":$jsonValue}"
        } else if (existing.containsMatchIn(metaJson)) {
            existing.replace(metaJson, "\"$key\":$jsonValue")
        } else {
            val inner = metaJson.trim().removeSuffix("}").trimEnd()
            val sep = if (inner.endsWith("{")) "" else ","
            "$inner$sep\"$key\":$jsonValue}"
        }
        File(dir(), "meta.json").writeText(metaJson + "\n")
    }

    private fun jsonStr(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                else -> b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    private fun fileLen(name: String): Int {
        val f = File(dir(), name)
        return if (f.isFile) f.length().toInt() else 0
    }

    private fun maybeMillerReady() {
        val program = fileLen("program.txt")
        val snap = fileLen("snapshot.txt")
        val it = fileLen("integrityToken.txt")
        if (program <= 0 || snap <= 0 || it <= 0) return
        if (!millerReady.compareAndSet(false, true)) return
        val out = dir()
        val eventId = field("eventId")
        File(out, "REPLAY.txt").writeText(
            "dir ${out.absolutePath}\n" +
                "mill: python3 harness/po_replay.py mill ${out.absolutePath}\n" +
                "sps:  python3 harness/po_replay.py sps ${out.absolutePath}\n",
        )
        println(
            "log[DEBUG] bg capture miller dir=${out.absolutePath} " +
                "program=$program snap=$snap it=$it eventId=$eventId",
        )
    }

    private fun maybeSabrReady() {
        val body = fileLen("sabr_body.b64url")
        val url = fileLen("sabr_url.txt")
        if (body <= 0 || url <= 0) return
        if (!sabrReady.compareAndSet(false, true)) return
        val out = dir()
        println(
            "log[DEBUG] bg capture sabr dir=${out.absolutePath} url=$url body=$body",
        )
    }
}

private fun utcStamp(): String =
    java.time.Instant.now().atOffset(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

private fun pointLatest(root: File, stamp: String) {
    val latest = File(root, "latest")
    try {
        val path = latest.toPath()
        java.nio.file.Files.deleteIfExists(path)
        java.nio.file.Files.createSymbolicLink(path, java.nio.file.Path.of(stamp))
    } catch (_: Exception) {
        latest.writeText(stamp + "\n")
    }
}

internal class StdoutLog private constructor(
    crumbs: List<Crumb>,
    private val proofFile: File,
    private val jar: CookieJar?,
    private val dumpedCookies: AtomicBoolean,
    private val capture: BgCapture,
) : CrumbLogger(crumbs) {
    constructor(proofFile: File, dumpRoot: File, jar: CookieJar) : this(
        emptyList(),
        proofFile,
        jar,
        AtomicBoolean(false),
        BgCapture(dumpRoot),
    )

    override fun fork(crumbs: List<Crumb>): CrumbLogger =
        StdoutLog(crumbs, proofFile, jar, dumpedCookies, capture)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        val line = "log[$level] $path $message${error?.let { " err=${it::class.simpleName}: ${it.message}" } ?: ""}"
        println(line)
        if (message.startsWith("bg dump ")) capture.ingest(message)
        if (!dumpedCookies.get() &&
            (message.indexOf("PO_REJECTED") >= 0 ||
                message.indexOf("bg sps=2") >= 0 ||
                message.indexOf("bg sps=3") >= 0)
        ) {
            if (dumpedCookies.compareAndSet(false, true)) dumpCookieJar()
        }
        if (message.indexOf("bg generateIT") >= 0 ||
            message.indexOf("minter GenerateIT") >= 0 ||
            message.indexOf("owl m ") >= 0 ||
            message.indexOf("SPS=") >= 0 ||
            message.indexOf("bg dump") >= 0 ||
            message.indexOf("bg capture") >= 0 ||
            message.indexOf("mse MediaSource") >= 0 ||
            message.indexOf("mse video.src") >= 0 ||
            message.indexOf("mse sourceopen") >= 0 ||
            message.indexOf("mse createObjectURL") >= 0 ||
            message.indexOf("mse append") >= 0 ||
            message.indexOf("mse changeType") >= 0 ||
            message.indexOf("mse isTypeSupported") >= 0 ||
            message.indexOf("mse setQuality") >= 0 ||
            message.indexOf("mse drain") >= 0 ||
            message.indexOf("FORMAT_INIT") >= 0 ||
            message.indexOf("MEDIA_HEADER") >= 0 ||
            message.indexOf("sabr HTTP") >= 0 ||
            message.indexOf("mse dummy SB") >= 0 ||
            message.indexOf("mse fake-fmt") >= 0 ||
            message.indexOf("mse sabr drop") >= 0 ||
            message.indexOf("mse sabr fake-ump") >= 0 ||
            message.indexOf("mse append fake-cover") >= 0 ||
            message.indexOf("mse isTypeSupported fake") >= 0 ||
            message.indexOf("getAvailableQualityData") >= 0 ||
            message.indexOf("player RSP") >= 0 ||
            message.indexOf("mse=[") >= 0 ||
            message.indexOf("sabr sabr=") >= 0 ||
            message.indexOf("mill snap ") >= 0
        ) {
            proofFile.appendText(line + "\n")
        }
    }

    private fun dumpCookieJar() {
        val jar = jar ?: return
        val dumpDir = capture.dir()
        dumpDir.mkdirs()
        val seen = LinkedHashSet<String>()
        val out = StringBuilder("# Netscape HTTP Cookie File\n")
        for (raw in listOf(
            "https://www.youtube.com/",
            "https://www.google.com/",
            "https://www.googlevideo.com/",
        )) {
            val url = raw.toHttpUrl()
            for (c in jar.loadForRequest(url)) {
                val key = c.domain + "\t" + c.path + "\t" + c.name
                if (!seen.add(key)) continue
                val domain = if (c.hostOnly) c.domain else "." + c.domain
                val httpOnly = if (c.httpOnly) "#HttpOnly_" else ""
                val includeSub = if (c.hostOnly) "FALSE" else "TRUE"
                val secure = if (c.secure) "TRUE" else "FALSE"
                val expires = if (c.persistent) (c.expiresAt / 1000).toString() else "0"
                out.append(httpOnly).append(domain).append('\t').append(includeSub).append('\t')
                    .append(c.path).append('\t').append(secure).append('\t').append(expires)
                    .append('\t').append(c.name).append('\t').append(c.value).append('\n')
            }
        }
        val file = File(dumpDir, "anon.cookies")
        file.writeText(out.toString())
    }
}

internal fun youtubeReachable(): Boolean {
    if (System.getenv("YMUSIC_OFFLINE") == "1") return false
    return try {
        val c = java.net.URI.create("https://www.youtube.com/generate_204").toURL()
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3_000
        c.readTimeout = 3_000
        c.requestMethod = "GET"
        c.connect()
        c.responseCode
        true
    } catch (_: Exception) {
        false
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class HeadlessSession(
    val main: DynamicEngineManager,
    override val player: DynamicEnginePlayer,
    val dispatcher: CloseableCoroutineDispatcher,
    override val log: Logger,
    val proofFile: File,
    private val client: KmpHttpClient,
    private val cache: CacheStore,
    private val jar: CookieJar,
    private val settings: SettingsStore,
) : MseSession {
    override suspend fun close() {
        player.close()
        main.close()
        dispatcher.close()
    }

    companion object {
        suspend fun boot(surface: MseVideoSurface): HeadlessSession {
            val jar = InMemoryCookieJar()
            val inner = OkKmpHttpClient.Builder().apply {
                defaultExecuteTimeout = 60.seconds
                /* Proxyman (127.0.0.1:9090) is the macOS system HTTPS proxy; do not use it. */
                direct()
            }.build()
            val proofFile = File.createTempFile("po-proof-", ".log").also { it.writeText("") }
            val dumpRoot = File(File(System.getProperty("user.dir"), "build"), "bg-dump")
            dumpRoot.mkdirs()
            val log = StdoutLog(proofFile, dumpRoot, jar)
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "ymusic-playground-engine-cache")
            cacheDir.deleteRecursively()
            val cache = desktopEngineCacheStore(cacheDir, log)
            val mediaDir = File(System.getProperty("java.io.tmpdir"), "ymusic-playground-media-cache")
            mediaDir.deleteRecursively()
            mediaDir.mkdirs()
            val driver = jvmSegmentDatabaseDriver(
                "jdbc:sqlite:${File(mediaDir, "cache-index.db").absolutePath}",
            )
            val mediaCache = MediaCache(
                DiskMediaBlobCache(
                    mediaDir.absolutePath.toPath(),
                    FileSystem.SYSTEM,
                    SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() }),
                ),
                SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
            )
            val proxy = LocalMediaProxy(
                mediaCache,
                inner,
                userAgent = DESKTOP_UA,
                logger = log,
            )
            val client = SabrProxyHttpClient(inner, proxy)
            val settings = InMemorySettingsStore()
            val main = DynamicEngineManager(
                client = client,
                appVersion = 1,
                logger = log,
                jsSource = embeddedEngineJsSource(),
                cacheStore = cache,
                cookieJar = jar,
                settings = settings,
            )
            // Main engine JS lives on its own QuickJS thread (DynamicEngineManager.start).
            main.start()
            // Player engine JS lives on a second QuickJS thread — never Compose/Swing Main.
            val dispatcher = QuickJsRuntime.newDispatcher()
            val player = withContext(dispatcher) {
                DynamicEnginePlayer.create(
                    client,
                    appVersion = 1,
                    dispatcher = dispatcher,
                    logger = log,
                    cacheStore = cache,
                    cookieJar = jar,
                    settings = settings,
                    videoSurface = surface,
                    main = main,
                )
            }
            return HeadlessSession(main, player, dispatcher, log, proofFile, client, cache, jar, settings)
        }
    }
}

internal actual suspend fun bootMseSession(surface: MseVideoSurface): MseSession =
    HeadlessSession.boot(surface)

internal actual fun defaultMseVideoId(): String =
    System.getenv("MSE_VIDEO_ID")?.takeIf { it.isNotBlank() } ?: "0_LTL7O1xX0"

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

internal actual fun logMseLab(line: String) = Unit
