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

/** Reassemble `bg dump` console chunks into files a later miller/SPS run can load. */
internal class BgCapture(val dumpDir: File) {
    private val parts = HashMap<String, Array<String?>>()
    private var metaJson = ""
    private val millerReady = AtomicBoolean(false)
    private val sabrReady = AtomicBoolean(false)

    @Synchronized
    fun ingest(message: String) {
        if (!message.startsWith("bg dump ")) return
        dumpDir.mkdirs()
        File(dumpDir, "console.txt").appendText(message + "\n")
        val rest = message.substring("bg dump ".length)
        when {
            rest.startsWith("meta ") -> {
                metaJson = rest.substring("meta ".length).trim()
                File(dumpDir, "meta.json").writeText(metaJson + "\n")
                maybeMillerReady()
            }
            rest.startsWith("sabr meta ") -> Unit
            rest.startsWith("entropy meta ") -> Unit
            rest.startsWith("timeOrigin ") ->
                File(dumpDir, "timeOrigin.txt").writeText(rest.substring("timeOrigin ".length).trim() + "\n")
            rest.startsWith("eventId ") ->
                File(dumpDir, "event_id.txt").writeText(rest.substring("eventId ".length).trim())
            rest.startsWith("interpUrl ") ->
                File(dumpDir, "interp_url.txt").writeText(rest.substring("interpUrl ".length).trim())
            rest.startsWith("mintId ") ->
                File(dumpDir, "mintId.txt").writeText(rest.substring("mintId ".length).trim())
            rest.startsWith("sps ") -> {
                val sps = rest.substring("sps ".length).trim()
                File(dumpDir, "sps.txt").writeText(sps + "\n")
                println("log[DEBUG] bg capture sps $sps dir=${dumpDir.absolutePath}")
            }
            else -> {
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
                    val joined = slot.joinToString("") { it!! }
                    writeKind(kind, joined)
                    maybeMillerReady()
                    maybeSabrReady()
                }
            }
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
        File(dumpDir, name).writeText(value)
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
        File(dumpDir, "challenge_inject.js").writeText("globalThis.__BBD_BG = $payload;\n")
    }

    private fun field(key: String): String {
        val m = Regex("\"$key\":\"([^\"]*)\"").find(metaJson) ?: return ""
        return m.groupValues[1]
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
        val f = File(dumpDir, name)
        return if (f.isFile) f.length().toInt() else 0
    }

    private fun maybeMillerReady() {
        val program = fileLen("program.txt")
        val snap = fileLen("snapshot.txt")
        val it = fileLen("integrityToken.txt")
        if (program <= 0 || snap <= 0 || it <= 0) return
        if (!millerReady.compareAndSet(false, true)) return
        val eventId = File(dumpDir, "event_id.txt").let { if (it.isFile) it.readText().trim() else field("eventId") }
        File(dumpDir, "REPLAY.txt").writeText(
            "dir ${dumpDir.absolutePath}\n" +
                "mill: python3 harness/po_replay.py mill ${dumpDir.absolutePath}\n" +
                "sps:  python3 harness/po_replay.py sps ${dumpDir.absolutePath}\n",
        )
        println(
            "log[DEBUG] bg capture miller dir=${dumpDir.absolutePath} " +
                "program=$program snap=$snap it=$it eventId=$eventId",
        )
    }

    private fun maybeSabrReady() {
        val body = fileLen("sabr_body.b64url")
        val url = fileLen("sabr_url.txt")
        if (body <= 0 || url <= 0) return
        if (!sabrReady.compareAndSet(false, true)) return
        println(
            "log[DEBUG] bg capture sabr dir=${dumpDir.absolutePath} url=$url body=$body",
        )
    }
}

internal class StdoutLog private constructor(
    crumbs: List<Crumb>,
    private val proofFile: File,
    private val dumpDir: File,
    private val jar: CookieJar?,
    private val dumpedCookies: AtomicBoolean,
    private val capture: BgCapture,
) : CrumbLogger(crumbs) {
    constructor(proofFile: File, dumpDir: File, jar: CookieJar) : this(
        emptyList(),
        proofFile,
        dumpDir,
        jar,
        AtomicBoolean(false),
        BgCapture(dumpDir),
    )

    override fun fork(crumbs: List<Crumb>): CrumbLogger =
        StdoutLog(crumbs, proofFile, dumpDir, jar, dumpedCookies, capture)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        val line = "log[$level] $path $message${error?.let { " err=${it::class.simpleName}: ${it.message}" } ?: ""}"
        println(line)
        if (message.startsWith("bg dump ")) capture.ingest(message)
        if (!dumpedCookies.get() &&
            (message.indexOf("PO_REJECTED") >= 0 || message.indexOf("bg dump meta") >= 0)
        ) {
            if (dumpedCookies.compareAndSet(false, true)) dumpCookieJar()
        }
        if (message.indexOf("minter GenerateIT") >= 0 ||
            message.indexOf("owl m ") >= 0 ||
            message.indexOf("SPS=") >= 0 ||
            message.indexOf("bg dump") >= 0 ||
            message.indexOf("bg capture") >= 0 ||
            message.indexOf("mse MediaSource") >= 0 ||
            message.indexOf("mse video.src") >= 0 ||
            message.indexOf("mse sourceopen") >= 0 ||
            message.indexOf("mse createObjectURL") >= 0 ||
            message.indexOf("mse append") >= 0 ||
            message.indexOf("mse dummy SB") >= 0 ||
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
            val dumpDir = File(File(System.getProperty("user.dir"), "build"), "bg-dump")
            dumpDir.mkdirs()
            val log = StdoutLog(proofFile, dumpDir, jar)
            println("log[DEBUG] bg-dump dir=${dumpDir.absolutePath}")
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
    System.getenv("MSE_VIDEO_ID")?.takeIf { it.isNotBlank() } ?: "wjhyk-Cx6wk"

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

internal actual fun logMseLab(line: String) = Unit
