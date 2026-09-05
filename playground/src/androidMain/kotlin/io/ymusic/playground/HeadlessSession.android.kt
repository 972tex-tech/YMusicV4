package io.ymusic.playground

import android.util.Log
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.InMemorySettingsStore
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private class AndroidMseSession(
    override val player: DynamicEnginePlayer,
    override val log: Logger,
    private val main: DynamicEngineManager,
    private val dispatcher: CloseableCoroutineDispatcher,
) : MseSession {
    override suspend fun close() {
        player.close()
        main.close()
        dispatcher.close()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal actual suspend fun bootMseSession(surface: MseVideoSurface): MseSession {
    val http = KmpHttpClient.create { defaultExecuteTimeout = 60.seconds }
    val log = PrintLog()
    val settings = InMemorySettingsStore()
    val main = DynamicEngineManager(
        http,
        appVersion = 1,
        logger = log,
        jsSource = embeddedEngineJsSource(),
        cookieJar = http.cookieJar,
        settings = settings,
    )
    main.start()
    val dispatcher = QuickJsRuntime.newDispatcher()
    val player = withContext(dispatcher) {
        DynamicEnginePlayer.create(
            http,
            appVersion = 1,
            dispatcher = dispatcher,
            logger = log,
            cookieJar = http.cookieJar,
            settings = settings,
            videoSurface = surface,
            main = main,
        )
    }
    return AndroidMseSession(player, log, main, dispatcher)
}

internal actual fun defaultMseVideoId(): String =
    System.getenv("MSE_VIDEO_ID")?.takeIf { it.isNotBlank() } ?: "N0vxXktZqXQ"

internal actual fun logMseLab(line: String) {
    Log.i("mse", "[android-mse] $line")
}

private class PrintLog private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
    constructor() : this(emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = PrintLog(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        val line = "log[$level] $path $message${error?.let { " err=${it.message}" } ?: ""}"
        Log.i("mse", line)
    }
}
