package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.decodeEngineError
import io.ymusic.dynamicengine.internal.ENGINE_JS
import io.ymusic.dynamicengine.internal.installMainProxy
import io.ymusic.dynamicengine.yt.jsonCommand
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseFrame
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.asBufferedSource
import io.ymusic.settings.SettingsStore
import kmpcommon.error.CodedError
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kmphttp.json.JsonReader
import kmphttp.json.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * YouTube playback via JS player commands. MSE is JS + native C (sync getters). The app supplies
 * a [videoSurface] for pictures and calls [play]/[pause]/[seekTo] — those are JS, not a Kotlin MSE API.
 */
class DynamicEnginePlayer private constructor(
    val engine: DynamicEngine,
    private val nativeMse: NativeMseHost?,
) {
    suspend fun init(
        logger: Logger? = null,
        width: Int? = null,
        height: Int? = null,
    ): JsonObject {
        return engine.jsonCommand(
            "player/init",
            { w ->
                if (width != null) w.name("width").value(width)
                if (height != null) w.name("height").value(height)
            },
            logger,
        ) { it.readObject() }
    }

    suspend fun load(
        videoId: String,
        logger: Logger? = null,
        quality: String = "zero",
        width: Int? = null,
        height: Int? = null,
    ): JsonObject {
        return engine.jsonCommand(
            "player/load",
            { w ->
                w.name("videoId").value(videoId)
                w.name("quality").value(quality)
                if (width != null) w.name("width").value(width)
                if (height != null) w.name("height").value(height)
            },
            logger,
        ) { it.readObject() }
    }

    /** Display hole size in CSS pixels. YouTube ABR uses this as the viewport. Default 640×360. */
    suspend fun setViewport(width: Int, height: Int, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setViewport",
            { w ->
                w.name("width").value(width)
                w.name("height").value(height)
            },
            logger,
        ) { it.skipValue() }
    }

    /** Cue the next video without interrupting current playback. */
    suspend fun preload(videoId: String, logger: Logger? = null): JsonObject {
        return engine.jsonCommand(
            "player/preload",
            { w -> w.name("videoId").value(videoId) },
            logger,
        ) { it.readObject() }
    }

    /** `"zero"` is audio-only (no video download/decode/render). Other values are YouTube quality tokens. */
    suspend fun setQuality(quality: String, logger: Logger? = null) {
        engine.jsonCommand("player/setQuality", { w -> w.name("quality").value(quality) }, logger) { it.skipValue() }
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

    /** Subscribe to clock/state until cancelled. Host close unsubscribes. */
    suspend fun collectStatus(onStatus: (JsonObject) -> Unit, logger: Logger? = null) {
        collectJsonPipe("listen/player/status", onStatus, logger)
    }

    /** Subscribe to qualities + caption until cancelled. Host close unsubscribes. */
    suspend fun collectCatalog(onCatalog: (JsonObject) -> Unit, logger: Logger? = null) {
        collectJsonPipe("listen/player/catalog", onCatalog, logger)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun collectJsonPipe(
        command: String,
        onObj: (JsonObject) -> Unit,
        logger: Logger?,
    ) {
        val pipe = engine.openPipe(command)
        try {
            val reader = JsonReader(pipe.source.asBufferedSource())
            while (true) {
                val obj = try {
                    reader.readObject()
                } catch (_: JsonSyntaxException) {
                    break
                } catch (e: PipeResetException) {
                    throw decodeEngineError(e.reason, e)
                }
                onObj(obj)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    suspend fun captionTracks(logger: Logger? = null): List<CaptionTrackInfo> {
        val o = engine.jsonCommand("player/captions", { }, logger) { it.readObject() }
        val arr = o["tracks"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val vss = t.str("vssId") ?: return@mapNotNull null
            CaptionTrackInfo(
                vssId = vss,
                languageCode = t.str("languageCode") ?: "",
                kind = t.str("kind") ?: "",
                displayName = t.str("displayName") ?: vss,
            )
        }
    }

    suspend fun setCaption(vssId: String?, logger: Logger? = null) {
        engine.jsonCommand(
            "player/setCaption",
            { w ->
                if (vssId == null) w.name("vssId").nullValue()
                else w.name("vssId").value(vssId)
            },
            logger,
        ) { it.skipValue() }
    }

    /** Subscribe to paint-on lines until cancelled. Host close pauses JS caption work. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun collectCaptionLines(onLines: (List<CaptionLine>) -> Unit, logger: Logger? = null) {
        val pipe = engine.openPipe("player/caption")
        try {
            val reader = JsonReader(pipe.source.asBufferedSource())
            while (true) {
                val obj = try {
                    reader.readObject()
                } catch (_: JsonSyntaxException) {
                    break
                } catch (e: PipeResetException) {
                    throw decodeEngineError(e.reason, e)
                }
                onLines(obj.captionLines())
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    /** Warm the singleton PO minter if needed, then mint a content PO token for [videoId]. */
    suspend fun cachedPlayerResponse(logger: Logger? = null): JsonObject =
        engine.jsonCommand("player/playerResponse", { }, logger) { it.readObject() }

    suspend fun mintContentPoToken(videoId: String, logger: Logger? = null): String =
        engine.jsonCommand("player/mintPo", { w -> w.name("videoId").value(videoId) }, logger) { r ->
            val o = r.readObject()
            val token = (o["contentPoToken"] as? JsonPrimitive)?.content.orEmpty()
            if (token.length < 20) error("missing contentPoToken")
            token
        }

    /** Android TextureView [Surface]; JVM Swing hole attaches via [MseVideoSurface.attach]. */
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

data class CaptionTrackInfo(
    val vssId: String,
    val languageCode: String,
    val kind: String,
    val displayName: String,
)

data class CaptionLine(
    val id: Int,
    val text: String,
)

internal fun JsonObject.longField(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.captionLines(): List<CaptionLine> {
    val arr = this["lines"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        CaptionLine(id = id, text = o.str("text").orEmpty())
    }
}
