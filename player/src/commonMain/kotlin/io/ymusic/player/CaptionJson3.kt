package io.ymusic.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun youtubeIdFromLocalUri(uri: String): String? {
    val name = uri.substringAfterLast('/').substringBeforeLast('.')
    val m = Regex("(?:-|_|\\s)([A-Za-z0-9_-]{11})$").find(name) ?: return null
    return m.groupValues[1]
}

internal fun parseJson3Captions(raw: String): List<InjectedCaptionCue> {
    val text = raw.trim().removePrefix("\uFEFF").let { s ->
        if (s.startsWith(")")) s.substringAfter('\n', s).trimStart() else s
    }
    if (text.isEmpty() || text[0] != '{') return emptyList()
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
    val events = root["events"] as? JsonArray ?: return emptyList()
    val out = ArrayList<InjectedCaptionCue>()
    for (el in events) {
        val ev = el as? JsonObject ?: continue
        val segs = ev["segs"] as? JsonArray ?: continue
        val startMs = (ev["tStartMs"] as? JsonPrimitive)?.doubleOrNull ?: continue
        val durMs = (ev["dDurationMs"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val body = buildString {
            for (s in segs) {
                val o = s as? JsonObject ?: continue
                append(o["utf8"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.replace('\n', ' ').trim()
        if (body.isEmpty()) continue
        val start = startMs / 1000.0
        val end = start + (if (durMs > 0) durMs / 1000.0 else 5.0)
        out.add(InjectedCaptionCue(start, end, body))
    }
    return out
}

internal fun withQuery(url: String, key: String, value: String): String {
    if (Regex("[?&]$key=").containsMatchIn(url)) return url
    return url + (if ('?' in url) "&" else "?") + key + "=" + value
}
