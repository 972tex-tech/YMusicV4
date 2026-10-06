package io.ymusic.download

/**
 * WebVTT → SubRip, for caption sidecars. Done here rather than by ffmpeg because the shipped ffmpeg has no
 * `srt` muxer, and iOS has no ffmpeg at all. Header/NOTE/STYLE blocks and cue settings are dropped,
 * inline tags (YouTube ASR's `<c>` word timing) stripped, cues renumbered from 1.
 */
internal object VttToSrt {

    private val TIMING = Regex("""((?:\d+:)?\d{2}:\d{2}\.\d{3})\s+-->\s+((?:\d+:)?\d{2}:\d{2}\.\d{3}).*""")
    private val TAG = Regex("<[^>]*>")
    private val BLOCK_BREAK = Regex("\n{2,}")
    private val SPACES = Regex(" {2,}")

    fun convert(vtt: String): String {
        val out = StringBuilder()
        var number = 0
        for (block in vtt.replace("\r\n", "\n").replace('\r', '\n').split(BLOCK_BREAK)) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timingAt = lines.indexOfFirst { TIMING.matchEntire(it.trim()) != null }
            if (timingAt < 0) continue
            val timing = TIMING.matchEntire(lines[timingAt].trim())!!
            val text = lines.drop(timingAt + 1).map { decodeEntities(it.replace(TAG, "")).replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
            if (text.isEmpty()) continue
            number++
            out.append(number).append('\n')
            out.append(srtTime(timing.groupValues[1])).append(" --> ").append(srtTime(timing.groupValues[2])).append('\n')
            text.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    /** `mm:ss.mmm` or `h:mm:ss.mmm` → `hh:mm:ss,mmm`. */
    private fun srtTime(vtt: String): String {
        val parts = vtt.split(':')
        val hours = if (parts.size == 3) parts[0].padStart(2, '0') else "00"
        val minutes = parts[parts.size - 2]
        val seconds = parts.last().replace('.', ',')
        return "$hours:$minutes:$seconds"
    }

    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&lrm;", "‎")
        .replace("&rlm;", "‏")
        .replace("&amp;", "&")
}
