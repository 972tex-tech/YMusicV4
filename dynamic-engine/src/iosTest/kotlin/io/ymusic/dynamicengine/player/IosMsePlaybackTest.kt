package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.InMemorySettingsStore
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmphttp.UrlSessionKmpHttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import projectcommon.json.longOrNull
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class IosMsePlaybackTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun mse_advances_clock_and_pulls_frames() = runBlocking {
        val t0 = TimeSource.Monotonic.markNow()
        fun nowMs(): Long = t0.elapsedNow().inWholeMilliseconds
        runCatching<Unit> {
            val s = AVAudioSession.sharedInstance()
            s.setCategory(AVAudioSessionCategoryPlayback, error = null)
        }
        val dispatcher = QuickJsRuntime.newDispatcher()
        val http = UrlSessionKmpHttpClient.Builder().apply { defaultExecuteTimeout = 60.seconds }.build()
        val log = PrintLog()
        val surface = MseVideoSurface()
        val main = DynamicEngineManager(
            http,
            appVersion = 1,
            logger = log,
            jsSource = embeddedEngineJsSource(),
            cookieJar = http.cookieJar,
            settings = InMemorySettingsStore(),
        )
        main.start()
        val player = withContext(dispatcher) {
            DynamicEnginePlayer.create(
                http,
                appVersion = 1,
                dispatcher = dispatcher,
                logger = log,
                cookieJar = http.cookieJar,
                settings = InMemorySettingsStore(),
                videoSurface = surface,
                main = main,
            )
        }
        try {
            withContext(dispatcher) { player.init(log) }
            withContext(dispatcher) { player.load(VIDEO_ID, log) }
            withContext(dispatcher) { player.play(log) }

            val deadline = nowMs() + 90_000
            var dur = 0L
            var frames = 0
            while (nowMs() < deadline) {
                val st = withContext(dispatcher) { player.status(log) }
                dur = st.longOrNull("durationMs") ?: 0L
                frames = player.mseFrames(surface.host)
                if (frames >= 2 && dur > 0) break
                delay(250)
            }
            println("ios-mse frames=$frames dur=$dur")
            assertTrue(dur > 0, "no duration after load frames=$frames")
            assertTrue(frames >= 1, "C host never pulled a video frame frames=$frames")

            withContext(dispatcher) { player.play(log) }
            delay(400)
            val moving = withContext(dispatcher) {
                player.status(log).longOrNull("positionMs") ?: 0L
            }
            delay(1_200)
            val later = withContext(dispatcher) {
                player.status(log).longOrNull("positionMs") ?: 0L
            }
            println("ios-mse clock $moving -> $later")
            assertTrue(later > moving, "play did not advance clock $moving -> $later")

            withContext(dispatcher) { player.pause(log) }
            delay(400)
            val pausedAt = withContext(dispatcher) {
                player.status(log).longOrNull("positionMs") ?: 0L
            }
            delay(800)
            val still = withContext(dispatcher) {
                player.status(log).longOrNull("positionMs") ?: 0L
            }
            println("ios-mse pause $pausedAt -> $still")
            assertTrue(abs(still - pausedAt) < 1_500, "clock kept moving while paused $pausedAt -> $still")
        } finally {
            withContext(dispatcher) { player.close() }
            main.close()
        }
    }

    private class PrintLog private constructor(crumbs: List<Crumb>) : CrumbLogger(crumbs) {
        constructor() : this(emptyList())

        override fun fork(crumbs: List<Crumb>): CrumbLogger = PrintLog(crumbs)

        override fun log(level: Logger.Level, message: String, error: Throwable?) {
            println("log[$level] $path $message${error?.let { " err=${it.message}" } ?: ""}")
        }
    }

    companion object {
        private const val VIDEO_ID = "jNQXAC9IVRw"
    }
}
