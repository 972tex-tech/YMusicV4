package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS
import io.ymusic.dynamicengine.internal.installMainProxy
import io.ymusic.dynamicengine.testEngineDispatcher
import io.ymusic.dynamicengine.yt.jsonCommand
import io.ymusic.settings.InMemorySettingsStore
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmphttp.InMemoryCookieJar
import kmphttp.OkKmpHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import projectcommon.json.intOrNull
import projectcommon.json.stringOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Live headless mint: real [KmpHttpClient] + cookie jar, homepage player config from main,
 * BotGuard VM, GenerateIT, content PO token for a public video. Skips only when the network
 * is unreachable; a BotGuard/VM failure is a real test failure.
 */
class ContentPoTokenMintTest {
    @Test
    fun mints_a_content_po_token() = runBlocking {
        val jar = InMemoryCookieJar()
        // JS cooks Cookie headers (cookies.ts); the client itself must not also inject them.
        val client = OkKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 60.seconds
        }.build()
        val log = PrintLog()
        val main = withContext(testEngineDispatcher) {
            DynamicEngine.create(
                client,
                appVersion = 1,
                dispatcher = testEngineDispatcher,
                logger = log,
                engineJs = ENGINE_TEST_JS,
                cookieJar = jar,
                settings = InMemorySettingsStore(),
            ).also { it.healthCheck() }
        }
        val engine = withContext(testEngineDispatcher) {
            DynamicEngine.createEngine(
                client,
                appVersion = 1,
                dispatcher = testEngineDispatcher,
                logger = log,
                engineJs = ENGINE_TEST_JS,
                cookieJar = jar,
                settings = InMemorySettingsStore(),
            ) { parked ->
                installMainProxy(parked) { cmd -> main.openPipe(cmd) }
            }.also { it.healthCheck() }
        }
        try {
            val rec = withContext(testEngineDispatcher) {
                engine.jsonCommand(
                    "player/mintPo",
                    { w -> w.name("videoId").value(VIDEO_ID) },
                    log,
                ) { it.readObject() }
            }
            println("mintPo rec=$rec")
            val token = rec.stringOrNull("contentPoToken").orEmpty()
            val len = rec.intOrNull("contentPoTokenLen") ?: token.length
            val step = rec.stringOrNull("step")
            val ytGets = rec.intOrNull("ytGets") ?: 0
            val eventIdGets = rec.intOrNull("eventIdGets") ?: 0
            println("mintPo ytGets=$ytGets eventIdGets=$eventIdGets len=$len")
            assertTrue(token.length >= 20, "token too short step=$step rec=$rec")
            assertTrue(len >= 20, "len=$len")
            assertTrue(ytGets >= 1, "window.yt not accessed during minter (pre-base.js) rec=$rec")
            assertTrue(eventIdGets >= 1, "yt.config_.EVENT_ID not accessed during minter (pre-base.js) rec=$rec")
        } catch (e: EngineError) {
            println("mintPo EngineError class=${e::class.simpleName} code=${e.code} msg=${e.message}")
            println("mintPo jsName=${e.jsName} jsStack=${e.jsStack}")
            e.cause?.let { println("mintPo cause=${it::class.simpleName} ${it.message}") }
            if (e is EngineError.Network) return@runBlocking
            throw e
        } finally {
            engine.close()
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
    }
}
