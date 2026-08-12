package io.ymusic.dynamicengine.internal

import io.ymusic.dynamicengine.WireError
import io.ymusic.quickjs.BrokenPipeException
import io.ymusic.quickjs.Pipe
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.quickjs.asAsyncSource
import io.ymusic.settings.SettingsStore
import kmpcommon.error.CodedError
import kmpcommon.error.codedError
import kmpcommon.io.BufferedAsyncSource
import kmpcommon.log.Logger
import kmphttp.json.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer

internal fun QuickJsRuntime.installSettings(settings: SettingsStore, logger: Logger?) {
    // Store touches leave the engine thread (the engine-thread rule, AGENTS.md):
    // SharedPreferences' first read blocks on disk, and on the engine thread that wedges every
    // command at once.
    onAccept("settings/get") { pipe ->
        serveSettings(pipe, logger, "settings/get") { head ->
            val key = head.requireString("key")
            val type = head.requireString("type")
            val value = withContext(Dispatchers.IO) {
                when (type) {
                    "string" -> JsonPrimitive(settings.string(key, head.requirePrimitive("default").content).value)
                    "boolean" -> JsonPrimitive(settings.boolean(key, head.requirePrimitive("default").booleanOrNull ?: badDefault()).value)
                    "int" -> JsonPrimitive(settings.int(key, head.requirePrimitive("default").intOrNull ?: badDefault()).value)
                    else -> throw SettingsRequestError("settings request has an invalid 'type'")
                }
            }
            val out = Buffer().writeUtf8(buildJsonObject { put("value", value) }.toString())
            pipe.sink.send(out, out.size)
        }
    }
    onAccept("settings/set") { pipe ->
        serveSettings(pipe, logger, "settings/set") { head ->
            val key = head.requireString("key")
            val value = head.requirePrimitive("value")
            val type = head.requireString("type")
            withContext(Dispatchers.IO) {
                when (type) {
                    "string" -> settings.putString(key, value.content)
                    "boolean" -> settings.putBoolean(key, value.booleanOrNull ?: badValue())
                    "int" -> settings.putInt(key, value.intOrNull ?: badValue())
                    else -> throw SettingsRequestError("settings request has an invalid 'type'")
                }
            }
        }
    }
}

private suspend fun serveSettings(
    pipe: Pipe,
    logger: Logger?,
    tag: String,
    handle: suspend (JsonObject) -> Unit,
) {
    var log = logger?.fork(tag)
    try {
        val head = try {
            JsonReader(BufferedAsyncSource(pipe.source.asAsyncSource())).readObject()
        } catch (_: PipeResetException) {
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log?.log(Logger.Level.WARN, "E20060 malformed settings head: ${e.message}", e)
            pipe.sink.close(reason(WireError(code = "E20060", message = "malformed settings head: ${e.message}")))
            return
        }
        log = protocolLogger(logger, head, tag)
        try {
            handle(head)
            pipe.sink.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BrokenPipeException) {
        } catch (e: Throwable) {
            val wire = classify(e)
            log?.log(Logger.Level.WARN, "${wire.code} ${wire.message}", e)
            runCatching { pipe.sink.close(reason(wire)) }
        }
    } finally {
        pipe.source.close()
    }
}

private fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
        ?: throw SettingsRequestError("settings request missing '$key'")

private fun JsonObject.requirePrimitive(key: String): JsonPrimitive =
    this[key] as? JsonPrimitive ?: throw SettingsRequestError("settings request missing '$key'")

private fun badDefault(): Nothing = throw SettingsRequestError("settings request has an invalid 'default'")

private fun badValue(): Nothing = throw SettingsRequestError("settings request has an invalid 'value'")

private class SettingsRequestError(message: String) : Exception(message), CodedError {
    override val code = "E20060"
    override val userMessage: String? = null
}

private fun classify(error: Throwable): WireError {
    val coded = error.codedError()
    val message = error.message ?: error.toString()
    return WireError(
        code = coded?.code ?: Regex("""E\d{5}""").find(message)?.value ?: "E20061",
        message = message,
        name = error::class.simpleName,
        userMessage = coded?.userMessage,
        stack = error.stackTraceToString().take(4096),
    )
}

private fun reason(wire: WireError): String = Json.encodeToString(wire)
