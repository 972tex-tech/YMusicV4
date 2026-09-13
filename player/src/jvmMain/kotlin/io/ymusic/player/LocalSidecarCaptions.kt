package io.ymusic.player

import java.io.File

internal data class LocalCue(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val text: String,
)

internal data class SidecarTrack(
    val id: String,
    val lang: String,
    val label: String,
    val cues: List<LocalCue>,
)

/** SRT/VTT next to a local file (`song.srt`, `song.vie.srt`). */
internal object LocalSidecarCaptions {
    fun find(mediaPath: String): List<SidecarTrack> {
        val media = File(mediaPath)
        val dir = media.parentFile ?: return emptyList()
        val stem = media.nameWithoutExtension
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<SidecarTrack>()
        for (f in files) {
            val ext = f.extension.lowercase()
            if (ext != "srt" && ext != "vtt") continue
            val name = f.nameWithoutExtension
            if (name != stem && !name.startsWith("$stem.")) continue
            val lang = if (name == stem) {
                "und"
            } else {
                name.removePrefix("$stem.").substringBefore('.').ifBlank { "und" }
            }
            val cues = parseTimedText(f.readText())
            if (cues.isEmpty()) continue
            val label = if (lang == "und") f.name else lang
            out.add(SidecarTrack("file:$lang", lang, label, cues))
        }
        return out
    }

    internal fun parseTimedText(raw: String): List<LocalCue> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trimStart('\uFEFF')
        val blocks = text.split(Regex("\n\n+"))
        val ts = Regex(
            """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""",
        )
        val out = ArrayList<LocalCue>()
        var id = 1
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")) continue
            val stamp = lines.firstOrNull { ts.containsMatchIn(it) } ?: continue
            val m = ts.find(stamp) ?: continue
            val start = hms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = hms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            val body = lines.dropWhile { !ts.containsMatchIn(it) }.drop(1)
                .joinToString("\n") { it.trim() }
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (body.isEmpty()) continue
            out.add(LocalCue(id++, start, end, body))
        }
        return out
    }

    private fun hms(h: String, m: String, s: String, frac: String): Double {
        val hours = h.toIntOrNull() ?: 0
        val ms = frac.padEnd(3, '0').take(3).toIntOrNull() ?: 0
        return hours * 3600.0 + (m.toIntOrNull() ?: 0) * 60.0 + (s.toIntOrNull() ?: 0) + ms / 1000.0
    }
}
