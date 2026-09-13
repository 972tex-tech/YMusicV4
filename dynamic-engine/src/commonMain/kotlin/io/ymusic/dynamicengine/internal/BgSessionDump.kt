package io.ymusic.dynamicengine.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path

private val dumpJson = Json { ignoreUnknownKeys = true }

/**
 * Explode a `yt.bg.session` cache JSON (written by engine-js over `cache/put`) into a miller dump
 * dir. Reproduce params never ride the logger — this is the cache blob as files.
 */
internal fun writeBgSessionDump(fs: FileSystem, root: Path, jsonUtf8: String, keyHint: String): Path {
    val obj = dumpJson.parseToJsonElement(jsonUtf8).jsonObject
    val key = obj.str("sessionKey")?.ifEmpty { null } ?: keyHint.ifEmpty { "session" }
    val dir = root / key
    fs.createDirectories(dir)
    fs.write(dir / "bg-session.json") { writeUtf8(jsonUtf8) }
    writeText(fs, dir / "program.txt", obj.str("program"))
    writeText(fs, dir / "snapshot.txt", obj.str("snapshot"))
    writeText(fs, dir / "integrityToken.txt", obj.str("integrityToken"))
    writeText(fs, dir / "pot.txt", obj.str("pot"))
    writeText(fs, dir / "entropy.txt", obj.str("entropy"))
    writeText(fs, dir / "sabr_url.txt", obj.str("sabrUrl"))
    writeText(fs, dir / "sabr_body.b64url", obj.str("sabrBody"))
    writeText(fs, dir / "visitorData.txt", obj.str("visitorData"))
    writeText(fs, dir / "interpreter.js", obj.str("interpJs"))
    val eventId = obj.str("eventId").orEmpty()
    val interpUrl = obj.str("interpUrl").orEmpty()
    val globalName = obj.str("globalName") ?: "trayride"
    val mintId = obj.str("mintId").orEmpty()
    val ua = obj.str("ua").orEmpty()
    val timeOrigin = obj.str("timeOrigin").orEmpty()
    val program = obj.str("program").orEmpty()
    val hash = interpUrl.substringAfterLast('/').removeSuffix(".js")
    val meta = buildJsonObject {
        put("source", "yt.bg.session")
        put("sessionKey", key)
        put("eventId", eventId)
        put("globalName", globalName)
        put("interpreterUrl", interpUrl)
        put("ua", ua)
        put("mintId", mintId)
        put("timeOrigin", timeOrigin)
        put("program_len", program.length)
        put("snapLen", obj.str("snapshot")?.length ?: 0)
        put("itLen", obj.str("integrityToken")?.length ?: 0)
        put("potLen", obj.str("pot")?.length ?: 0)
        put("entropyLen", obj.str("entropy")?.length ?: 0)
        put(
            "complete",
            program.isNotEmpty() &&
                !obj.str("snapshot").isNullOrEmpty() &&
                !obj.str("integrityToken").isNullOrEmpty() &&
                !obj.str("entropy").isNullOrEmpty(),
        )
    }
    fs.write(dir / "meta.json") { writeUtf8(meta.toString() + "\n") }
    if (program.isNotEmpty()) {
        val payload = buildJsonObject {
            put("globalName", globalName)
            put("interpreterHash", hash)
            put("program", program)
            put("requestKey", "O43z0dpjhgX20SCx4KAo")
            put("source", "yt.bg.session")
        }
        fs.write(dir / "challenge_inject.js") {
            writeUtf8("globalThis.__BBD_BG = $payload;\n")
        }
    }
    fs.write(dir / "REPLAY.txt") {
        writeUtf8(
            "dir $dir\n" +
                "mill: python3 harness/po_replay.py mill $dir\n" +
                "sps:  python3 harness/po_replay.py sps $dir\n",
        )
    }
    fs.write(root / "latest") { writeUtf8(key + "\n") }
    return dir
}

private fun JsonObject.str(field: String): String? {
    val p = this[field] as? JsonPrimitive ?: return null
    return p.contentOrNull?.takeIf { it.isNotEmpty() }
}

private fun writeText(fs: FileSystem, path: Path, value: String?) {
    if (value.isNullOrEmpty()) return
    fs.write(path) { writeUtf8(value) }
}
