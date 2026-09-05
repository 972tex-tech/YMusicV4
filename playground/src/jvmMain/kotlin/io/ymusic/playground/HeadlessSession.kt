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
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.time.Duration.Companion.seconds

internal class StdoutLog private constructor(
    crumbs: List<Crumb>,
    private val proofFile: File,
) : CrumbLogger(crumbs) {
    constructor(proofFile: File) : this(emptyList(), proofFile)

    override fun fork(crumbs: List<Crumb>): CrumbLogger = StdoutLog(crumbs, proofFile)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        val line = "log[$level] $path $message${error?.let { " err=${it::class.simpleName}: ${it.message}" } ?: ""}"
        println(line)
        if (message.indexOf("minter GenerateIT") >= 0 ||
            message.indexOf("owl m ") >= 0 ||
            message.indexOf("SPS=") >= 0 ||
            message.indexOf("mse MediaSource") >= 0 ||
            message.indexOf("mse video.src") >= 0 ||
            message.indexOf("mse sourceopen") >= 0 ||
            message.indexOf("mse createObjectURL") >= 0 ||
            message.indexOf("mse append") >= 0 ||
            message.indexOf("mse dummy SB") >= 0 ||
            message.indexOf("player RSP") >= 0 ||
            message.indexOf("mse=[") >= 0 ||
            message.indexOf("sabr sabr=") >= 0
        ) {
            proofFile.appendText(line + "\n")
        }
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
            val log = StdoutLog(proofFile)
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
    System.getenv("MSE_VIDEO_ID")?.takeIf { it.isNotBlank() } ?: "Vm4MIgcpSWI"

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

internal actual fun logMseLab(line: String) = Unit
