package io.ymusic.download

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

/**
 * WebVTT caption → LRC lyrics (`[mm:ss.xx]line`), the text MP3 players read as timed lyrics. One line per
 * caption line, stamped with its cue's start. YouTube's auto-generated captions roll — each cue repeats
 * the line before it, and a 10 ms cue repeats the new line again — so a line equal to either of the two
 * last emitted ones is skipped.
 */
internal object VttToLrc {

    private val TIMING = Regex("""((?:\d+:)?\d{2}:\d{2}\.\d{3})\s+-->\s+(?:\d+:)?\d{2}:\d{2}\.\d{3}.*""")
    private val TAG = Regex("<[^>]*>")
    private val SPACES = Regex("\\s{2,}")
    private val BLOCK_BREAK = Regex("\n{2,}")

    fun convert(vtt: String): String {
        val out = StringBuilder()
        val recent = ArrayDeque<String>()
        for (block in vtt.replace("\r\n", "\n").replace('\r', '\n').split(BLOCK_BREAK)) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timingAt = lines.indexOfFirst { TIMING.matchEntire(it.trim()) != null }
            if (timingAt < 0) continue
            val stamp = lrcTime(TIMING.matchEntire(lines[timingAt].trim())!!.groupValues[1])
            for (raw in lines.drop(timingAt + 1)) {
                val line = decodeEntities(raw.replace(TAG, "")).replace(SPACES, " ").trim()
                if (line.isEmpty() || line in recent) continue
                out.append(stamp).append(line).append('\n')
                recent.addLast(line)
                if (recent.size > 2) recent.removeFirst()
            }
        }
        return out.toString()
    }

    /** `h:mm:ss.mmm` / `mm:ss.mmm` → `[mm:ss.xx]`, minutes counting past 60. */
    private fun lrcTime(vtt: String): String {
        val parts = vtt.split(':')
        val hours = if (parts.size == 3) parts[0].toInt() else 0
        val minutes = hours * 60 + parts[parts.size - 2].toInt()
        val (seconds, millis) = parts.last().split('.').let { it[0] to it[1] }
        return "[${minutes.toString().padStart(2, '0')}:$seconds.${millis.take(2)}]"
    }

    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&lrm;", "‎")
        .replace("&rlm;", "‏")
        .replace("&amp;", "&")
}

/** One caption track as MP3 lyrics: ISO-639-2 [language], the track [description] and LRC [text]. */
internal class Lyrics(val language: String, val description: String, val text: String)

/**
 * Writes lyrics into an MP3's ID3v2.3 tag as USLT frames — the field players read lyrics from. ffmpeg
 * can't write USLT (it falls back to a TXXX that nothing reads as lyrics), so the frames are inserted after
 * ffmpeg made the file (with `-id3v2_version 3`).
 */
internal object Id3Lyrics {

    private const val HEADER_SIZE = 10
    private const val ENCODING_UTF16_BOM: Int = 1

    /**
     * Copies the MP3 in [source] to [sink] with [lyrics] added as USLT frames, placed first in the tag body
     * so they land before any padding. A file without a tag gets a new ID3v2.3 one. Returns false — and
     * writes nothing — for a tag it can't extend safely (another version, unsynchronisation, extended
     * header), so the caller keeps the original file.
     */
    fun insert(source: BufferedSource, sink: BufferedSink, lyrics: List<Lyrics>): Boolean {
        val frames = Buffer()
        lyrics.forEach { writeUslt(frames, it) }
        val peek = source.peek()
        val hasTag = peek.request(HEADER_SIZE.toLong()) && peek.readUtf8(3) == "ID3"
        if (!hasTag) {
            writeHeader(sink, frames.size)
            sink.writeAll(frames)
            sink.writeAll(source)
            return true
        }
        val header = source.peek().readByteString(HEADER_SIZE.toLong())
        val major = header[3].toInt()
        val flags = header[5].toInt() and 0xFF
        if (major != 3 || flags != 0) return false
        val bodySize = syncsafe(header[6], header[7], header[8], header[9])
        source.skip(HEADER_SIZE.toLong())
        writeHeader(sink, bodySize + frames.size)
        sink.writeAll(frames)
        sink.write(source, bodySize)
        sink.writeAll(source)
        return true
    }

    private fun writeHeader(sink: BufferedSink, bodySize: Long) {
        sink.writeUtf8("ID3")
        sink.writeByte(3).writeByte(0) // v2.3.0
        sink.writeByte(0) // flags
        sink.writeByte(((bodySize shr 21) and 0x7F).toInt())
        sink.writeByte(((bodySize shr 14) and 0x7F).toInt())
        sink.writeByte(((bodySize shr 7) and 0x7F).toInt())
        sink.writeByte((bodySize and 0x7F).toInt())
    }

    /** USLT: encoding, 3-letter language, a terminated content descriptor, then the lyrics text. */
    private fun writeUslt(out: Buffer, lyrics: Lyrics) {
        val body = Buffer()
        body.writeByte(ENCODING_UTF16_BOM)
        body.writeUtf8(lyrics.language.take(3).padEnd(3, ' '))
        writeUtf16(body, lyrics.description)
        body.writeByte(0).writeByte(0)
        writeUtf16(body, lyrics.text)
        out.writeUtf8("USLT")
        out.writeInt(body.size.toInt()) // v2.3 frame sizes are plain big-endian, not syncsafe
        out.writeShort(0) // frame flags
        out.writeAll(body)
    }

    /** UTF-16 with a little-endian BOM — v2.3's only Unicode encoding. */
    private fun writeUtf16(out: Buffer, text: String) {
        out.writeByte(0xFF).writeByte(0xFE)
        text.forEach { out.writeShortLe(it.code) }
    }

    private fun syncsafe(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Long =
        ((b0.toLong() and 0x7F) shl 21) or ((b1.toLong() and 0x7F) shl 14) or
            ((b2.toLong() and 0x7F) shl 7) or (b3.toLong() and 0x7F)
}
