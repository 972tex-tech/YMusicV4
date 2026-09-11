package kmpffmpeg.metadata

import kmpcommon.uri.Uri

/**
 * Parses the human-readable dump that `ffmpeg -i <input>` prints to **stderr** into a
 * [MediaFileMetadata]. The trimmed prebuilt ships **no ffprobe**, so we can't take the legacy
 * ffprobe-JSON path — this scrapes the `Input #0 … / Metadata: / Duration: / Stream #…` text instead.
 *
 * That text is lossier than ffprobe JSON, so a few fields differ from the legacy parser by necessity:
 *  - per-stream `duration` is not printed for most containers → [StreamMetadata.Audio.duration] is null
 *    (the container duration is still parsed);
 *  - stream language is printed in the `Stream #0:0(eng):` header, not under `tags`, so we fold a
 *    non-`und` language into that stream's [MetadataMap] under the `language` key;
 *  - bitrates are printed in `kb/s` (3 sig. figs) and scaled to bits/s, so they're approximate.
 *
 * Only the **first** input (`Input #0`) is parsed. The [uri] is supplied by the caller (the path
 * echoed in the `from '…'` line is ignored — it's just whatever string ffmpeg was handed). Throws
 * [FfmpegInfoParseException] when the dump has no `Input #` block (e.g. ffmpeg rejected the file) so
 * callers can surface the underlying error.
 */
object FfmpegInfoParser {

    private val INPUT = Regex("""^Input #\d+, (.+), from '(.*)':\s*$""")
    private val DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+)(?:\.(\d+))?""")
    private val CONTAINER_BITRATE = Regex("""bitrate:\s*(\d+)\s*kb/s""")
    private val STREAM = Regex(
        """^Stream #\d+:(\d+)(?:\[[^\]]*\])?(?:\(([^)]+)\))?:\s*(Audio|Video|Subtitle|Data|Attachment):\s*(.*)$"""
    )
    private val HZ = Regex("""(\d+)\s*Hz""")
    private val KBPS = Regex("""(\d+)\s*kb/s""")
    private val DIMENSIONS = Regex("""^(\d+)x(\d+)""")          // anchored: skips hex tags like 0x6134706D
    private val FPS = Regex("""([\d.]+)\s*fps""")
    private val CHANNELS_N = Regex("""^(\d+)\s+channels""")
    private val PAREN = Regex("""\(([^)]+)\)""")

    /** ffmpeg channel-layout names → channel count. Anything else falls back to the `N channels` form. */
    private val CHANNEL_LAYOUTS = mapOf(
        "mono" to 1, "stereo" to 2, "downmix" to 2,
        "2.1" to 3, "3.0" to 3, "3.0(back)" to 3,
        "quad" to 4, "quad(side)" to 4, "4.0" to 4, "3.1" to 4,
        "5.0" to 5, "5.0(side)" to 5, "4.1" to 5,
        "5.1" to 6, "5.1(side)" to 6, "6.0" to 6, "6.0(front)" to 6, "hexagonal" to 6,
        "6.1" to 7, "6.1(back)" to 7, "6.1(front)" to 7, "7.0" to 7, "7.0(front)" to 7,
        "7.1" to 8, "7.1(wide)" to 8, "7.1(wide-side)" to 8, "octagonal" to 8,
    )

    /** av_disposition flag names ffmpeg prints in trailing parens, normalized to ffprobe's key spelling. */
    private val DISPOSITIONS = setOf(
        "default", "dub", "original", "comment", "lyrics", "karaoke", "forced",
        "hearing_impaired", "visual_impaired", "clean_effects", "attached_pic",
        "timed_thumbnails", "captions", "descriptions", "metadata", "dependent",
        "still_image", "non_diegetic", "multilayer",
    )

    fun parse(uri: Uri, stderr: String): MediaFileMetadata {
        val lines = stderr.lines()
        val inputIdx = lines.indexOfFirst { INPUT.matches(it.trimEnd()) }
        if (inputIdx < 0) {
            throw FfmpegInfoParseException(
                "no 'Input #' block in ffmpeg output", stderr.lines().takeLast(5).joinToString("\n")
            )
        }
        val containerName = INPUT.matchEntire(lines[inputIdx].trimEnd())!!.groupValues[1]

        var containerDurationMs: Long? = null
        var containerBitrate: Long? = null
        val containerMeta = LinkedHashMap<String, String?>()
        val streams = mutableListOf<StreamMetadata>()

        // Tag entries belong to whatever block is currently "open": the container's Metadata, or the
        // most recent stream's Metadata. `metaTarget` points at that map (or null = ignore tags).
        var metaTarget: MutableMap<String, String?>? = null

        for (raw in lines.subList(inputIdx + 1, lines.size)) {
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { it != ' ' }.let { if (it < 0) raw.length else it }
            val content = raw.trim()

            if (INPUT.matches(raw.trimEnd())) break // only the first input

            when {
                // ---- container-level lines (indent 2) ----
                indent <= 2 && content == "Metadata:" -> metaTarget = containerMeta

                indent <= 2 && content.startsWith("Duration:") -> {
                    metaTarget = null
                    containerDurationMs = parseDurationMs(content)
                    containerBitrate = CONTAINER_BITRATE.find(content)
                        ?.groupValues?.get(1)?.toLong()?.times(1000)
                }

                indent <= 2 && content.startsWith("Stream #") -> {
                    metaTarget = null
                    parseStream(content)?.let { (stream, meta) ->
                        streams += stream
                        metaTarget = meta // a following `Metadata:` block targets this stream
                    }
                }

                // ---- nested `Metadata:` header (indent 4, under a stream) ----
                indent in 3..4 && content == "Metadata:" -> {
                    // metaTarget already points at the stream's map (set when the Stream line was parsed).
                }

                // ---- a tag entry (`key : value`) belonging to the open block ----
                metaTarget != null && indent >= 4 && ':' in content -> {
                    val key = content.substringBefore(':').trim()
                    val value = content.substringAfter(':').trim()
                    if (key.isNotEmpty()) metaTarget[key] = value
                }

                // Any other indent<=4 header (Side data:, Chapters:, …) closes the current tag block.
                indent <= 4 -> metaTarget = null
            }
        }

        return MediaFileMetadata(
            uri = uri,
            container = ContainerMetadata(
                name = containerName,
                size = null, // not printed by `ffmpeg -i`
                duration = containerDurationMs,
                bitrate = containerBitrate,
                metadataMap = if (containerMeta.isEmpty()) MetadataMap.EMPTY else MetadataMap(containerMeta),
            ),
            streams = streams,
        )
    }

    /**
     * Parses one `Stream #…` line into a [StreamMetadata] plus the (mutable) tag map a following
     * `Metadata:` block will fill. Returns null for stream kinds we don't model (Data/Attachment).
     */
    private fun parseStream(content: String): Pair<StreamMetadata, MutableMap<String, String?>>? {
        val m = STREAM.matchEntire(content) ?: return null
        val index = m.groupValues[1].toInt()
        val language = m.groupValues[2].takeIf { it.isNotEmpty() && it != "und" }
        val type = m.groupValues[3]
        val rest = m.groupValues[4]

        val params = splitParams(rest)
        val codec = params.firstOrNull()?.substringBefore(' ')?.trim().orEmpty()
        val disposition = PAREN.findAll(rest)
            .map { it.groupValues[1].trim().replace(' ', '_') }
            .filter { it in DISPOSITIONS }
            .toSet()

        // Seed the stream's tag map with the header language so MetadataMap.language resolves.
        val meta = LinkedHashMap<String, String?>()
        if (language != null) meta["language"] = language

        val stream: StreamMetadata = when (type) {
            "Audio" -> StreamMetadata.Audio(
                index = index,
                codec = codec,
                duration = null, // not printed per-stream in the text dump
                sample = params.firstNotNullOfOrNull { HZ.find(it)?.groupValues?.get(1)?.toLong() },
                channels = params.firstNotNullOfOrNull { parseChannels(it) },
                bitrate = params.firstNotNullOfOrNull { KBPS.find(it)?.groupValues?.get(1)?.toLong()?.times(1000) },
                metadataMap = MetadataMap(meta),
                disposition = disposition,
            )

            "Video" -> {
                val dims = params.firstNotNullOfOrNull { DIMENSIONS.find(it) }
                StreamMetadata.Video(
                    index = index,
                    codec = codec,
                    duration = null,
                    width = dims?.groupValues?.get(1)?.toInt(),
                    height = dims?.groupValues?.get(2)?.toInt(),
                    fps = params.firstNotNullOfOrNull { FPS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
                    metadataMap = MetadataMap(meta),
                    disposition = disposition,
                )
            }

            "Subtitle" -> StreamMetadata.Text(
                index = index,
                codec = codec,
                metadataMap = MetadataMap(meta),
                disposition = disposition,
            )

            else -> return null // Data / Attachment — not modeled
        }
        return stream to meta
    }

    /** `HH:MM:SS.ff` → milliseconds. The fractional part is whatever precision ffmpeg printed. */
    private fun parseDurationMs(content: String): Long? {
        val m = DURATION.find(content) ?: return null
        val (h, min, s) = listOf(1, 2, 3).map { m.groupValues[it].toLong() }
        val frac = m.groupValues[4]
        val fracMs = if (frac.isEmpty()) 0L else ("0.$frac".toDouble() * 1000).toLong()
        return ((h * 3600 + min * 60 + s) * 1000) + fracMs
    }

    private fun parseChannels(param: String): Int? {
        val p = param.trim()
        CHANNEL_LAYOUTS[p]?.let { return it }
        return CHANNELS_N.find(p)?.groupValues?.get(1)?.toInt()
    }

    /** Split a stream param list on top-level commas only — parens/brackets may themselves contain
     *  commas (`rgb24(pc, gbr/unknown/unknown)`, `[SAR 1:1 DAR 1:1]`). */
    private fun splitParams(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '(', '[' -> { depth++; sb.append(c) }
                ')', ']' -> { if (depth > 0) depth--; sb.append(c) }
                ',' -> if (depth == 0) { out.add(sb.toString().trim()); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }
}

/** Thrown by [FfmpegInfoParser.parse] when stderr has no parseable `Input #` block. */
class FfmpegInfoParseException(message: String, val stderrTail: String) :
    Exception("$message\n$stderrTail")
