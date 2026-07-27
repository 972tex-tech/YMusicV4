package ymusic.dynamicengine.internal

import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import okio.Buffer
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.QuickJsRuntime

/** The device's content locale: ISO language ("vi") to region ("VN"), never blank. */
internal expect fun deviceLocale(): Pair<String, String>

/**
 * Serves `"device/locale"` — platform facts the engine JS cannot see. The engine's locale ladder
 * (locale.ts) uses it as the seed below the user's pick and the connection's detected region:
 * ```
 * device/locale  JS→K: {}   K→JS: {"hl":"vi","gl":"VN"}   (then EOF)
 * ```
 * One-shot (HOST_PROTOCOLS §0). The reply is composed here from [deviceLocale]; there is nothing
 * to classify — a platform locale read cannot meaningfully fail.
 */
internal fun QuickJsRuntime.installDevice(logger: Logger?) {
    onAccept("device/locale") { pipe ->
        try {
            val (hl, gl) = deviceLocale()
            val out = Buffer().writeUtf8("""{"hl":${jsonString(hl)},"gl":${jsonString(gl)}}""")
            pipe.sink.send(out, out.size)
            pipe.sink.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: PipeResetException) {
            // the engine abandoned the ask
        } catch (e: Throwable) {
            logger?.log(Logger.Level.WARN, DEVICE_SCOPE, "device/locale failed: ${e.message}", e)
            runCatching { pipe.sink.close("device/locale failed") }
        } finally {
            pipe.source.close()
        }
    }
}

private val DEVICE_SCOPE = listOf("device/locale")

private fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
