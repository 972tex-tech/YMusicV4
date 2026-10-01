package io.ymusic.ui.sponsorblock

internal fun encodeSkipSegments(segments: List<SponsorBlockSegment>): String = buildString {
    append('[')
    segments.forEachIndexed { i, s ->
        if (i > 0) append(',')
        append("{\"startTime\":").append(s.startTime)
        append(",\"endTime\":").append(s.endTime)
        append(",\"category\":\"").append(escapeJson(s.category))
        append("\",\"videoDuration\":").append(s.videoDuration).append('}')
    }
    append(']')
}

internal fun decodeSkipSegments(raw: String): List<SponsorBlockSegment>? {
    if (raw.isEmpty() || raw == "[]") return emptyList()
    val out = mutableListOf<SponsorBlockSegment>()
    val obj = Regex("""\{[^{}]+\}""")
    for (m in obj.findAll(raw)) {
        val body = m.value
        val start = numberField(body, "startTime") ?: return null
        val end = numberField(body, "endTime") ?: return null
        val cat = stringField(body, "category") ?: return null
        val dur = numberField(body, "videoDuration") ?: return null
        out += SponsorBlockSegment(start, end, cat, dur)
    }
    return out
}

private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun numberField(body: String, name: String): Double? =
    Regex(""""$name"\s*:\s*(-?\d+(?:\.\d+)?)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()

private fun stringField(body: String, name: String): String? {
    val m = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(body) ?: return null
    return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}
