package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.internal.ENGINE_JS
import io.ymusic.dynamicengine.internal.installMainProxy
import io.ymusic.dynamicengine.yt.jsonCommand
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseFrame
import io.ymusic.settings.SettingsStore
import kmpcommon.error.CodedError
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * YouTube playback via JS player commands. MSE is JS + native C (sync getters). The app supplies
 * a [videoSurface] for pictures and calls [play]/[pause]/[seekTo] — those are JS, not a Kotlin MSE API.
 */
class DynamicEnginePlayer private constructor(
    val engine: DynamicEngine,
    private val nativeMse: NativeMseHost?,
) {
    suspend fun init(logger: Logger? = null): JsonObject {
        return engine.jsonCommand("player/init", { }, logger) { it.readObject() }
    }

    suspend fun load(videoId: String, logger: Logger? = null): JsonObject {
        return engine.jsonCommand("player/load", { w -> w.name("videoId").value(videoId) }, logger) { it.readObject() }
    }

    suspend fun play(logger: Logger? = null) {
        engine.jsonCommand("player/play", { }, logger) { it.skipValue() }
    }

    suspend fun pause(logger: Logger? = null) {
        engine.jsonCommand("player/pause", { }, logger) { it.skipValue() }
    }

    suspend fun seekTo(positionMs: Long, logger: Logger? = null) {
        engine.jsonCommand("player/seek", { w -> w.name("positionMs").value(positionMs) }, logger) { it.skipValue() }
    }

    suspend fun stop(logger: Logger? = null) {
        engine.jsonCommand("player/stop", { }, logger) { it.skipValue() }
    }

    suspend fun status(logger: Logger? = null): JsonObject {
        return engine.jsonCommand("player/status", { }, logger) { it.readObject() }
    }

    /** Warm the singleton PO minter if needed, then mint a content PO token for [videoId]. */
    suspend fun mintContentPoToken(videoId: String, logger: Logger? = null): String =
        engine.jsonCommand("player/mintPo", { w -> w.name("videoId").value(videoId) }, logger) { r ->
            val o = r.readObject()
            val token = (o["contentPoToken"] as? JsonPrimitive)?.content.orEmpty()
            if (token.length < 20) error("missing contentPoToken")
            token
        }

    /** Android: pass the TextureView [Surface] when it appears; null on destroy. JVM bitmap path ignores this. */
    fun setVideoSurface(surface: MseVideoSurface?) {
        nativeMse?.setVideoSurface(surface)
    }

    fun mseFrames(handle: Long): Int = MseHostNative.frames(handle)

    fun mseCopyFrame(handle: Long, afterGen: Int = -1): NativeMseFrame? =
        MseHostNative.copyFrame(handle, afterGen)

    suspend fun close() {
        /* Stop JS (__mse*) before freeing the C host. */
        engine.close()
        nativeMse?.close()
    }

    companion object {
        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            logger: Logger? = null,
            engineJs: String = ENGINE_JS,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            videoSurface: MseVideoSurface? = null,
            main: DynamicEngineManager? = null,
        ): DynamicEnginePlayer {
            var nativeMse: NativeMseHost? = null
            val engine = DynamicEngine.createEngine(
                client, appVersion, dispatcher, logger, engineJs, cacheStore, cookieJar, settings,
            ) { parked ->
                if (videoSurface != null) nativeMse = bindNativeMse(this, videoSurface)
                installMainProxy(parked) { cmd ->
                    val mgr = main ?: throw NoMainEngine()
                    mgr.use { it.openPipe(cmd) }
                }
            }
            engine.healthCheck()
            return DynamicEnginePlayer(engine, nativeMse)
        }
    }
}

internal interface NativeMseHost {
    fun setVideoSurface(surface: MseVideoSurface?)
    fun close()
}

internal expect fun bindNativeMse(runtime: io.ymusic.quickjs.QuickJsRuntime, surface: MseVideoSurface): NativeMseHost

internal class NoMainEngine : Exception("no main engine"), CodedError {
    override val code = "E20078"
    override val userMessage: String? = null
}

internal fun JsonObject.longField(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
