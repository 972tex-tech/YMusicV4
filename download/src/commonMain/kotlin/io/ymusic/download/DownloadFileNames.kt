package io.ymusic.download

/**
 * The one file-name policy for downloads, shared by the options dialog (live editing) and the pipeline
 * (publish). Forbidden are the characters any of Windows / macOS / Android SAF reject in a name — plus
 * control characters — so a name that works here works on every platform's storage.
 */
object DownloadFileNames {

    /** The characters a file name may never contain, as shown to the user. */
    const val FORBIDDEN_DISPLAY = "\\ / : * ? \" < > |"

    /** A stem's UTF-8 budget: file systems cap a name at 255 bytes, minus room for `_N` and the extension. */
    const val MAX_STEM_BYTES = 200

    private val FORBIDDEN_RUN = Regex("\\s*[\\x00-\\x1F\\x7F\\\\/:*?\"<>|]+\\s*")
    private val REPEATED_SEPARATOR = Regex("( - )+")

    private fun Char.isForbidden(): Boolean = this in "\\/:*?\"<>|" || this < ' ' || this == '\u007F'

    /** [input] with every forbidden character dropped — what an edit field keeps as the user types. */
    fun stripForbidden(input: String): String = input.filterNot { it.isForbidden() }

    /** True when [input] holds at least one forbidden character. */
    fun hasForbidden(input: String): Boolean = input.any { it.isForbidden() }

    /** The default stem for a video [title]: each run of forbidden characters (with the spaces around it)
     *  becomes " - ", so "ARTIST | SONG" reads "ARTIST - SONG" rather than gluing the words together. */
    fun fromTitle(title: String): String =
        finalize(title.replace(FORBIDDEN_RUN, " - ").replace(REPEATED_SEPARATOR, " - "))

    /** [fileName] if [taken] says it is free, else `stem_1.ext`, `stem_2.ext`… — a download never
     *  overwrites a file, whatever the destination (local folder or Android SAF). */
    fun freeName(fileName: String, taken: (String) -> Boolean): String {
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val stem = fileName.substring(0, dot)
        val ext = fileName.substring(dot)
        var n = 1
        while (taken("${stem}_$n$ext")) n++
        return "${stem}_$n$ext"
    }

    /** The stem actually written: forbidden characters dropped, spaces collapsed, no leading/trailing
     *  spaces, dashes or dots (Windows rejects a trailing dot or space), capped at [MAX_STEM_BYTES]. May be
     *  empty — the caller falls back to something non-empty. */
    fun finalize(input: String): String =
        capBytes(
            stripForbidden(input)
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.', '-'),
            MAX_STEM_BYTES,
        ).trimEnd(' ', '.')

    /** The longest prefix of [input] within [maxBytes] of UTF-8, cut between code points, with unpaired
     *  surrogates dropped — SAF rejects a name that doesn't encode ("Malformed input"). */
    fun capBytes(input: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char.isSurrogate()) {
                val paired = char.isHighSurrogate() && index + 1 < input.length && input[index + 1].isLowSurrogate()
                if (paired) {
                    if (bytes + 4 > maxBytes) break
                    out.append(input, index, index + 2)
                    bytes += 4
                    index += 2
                } else {
                    index++
                }
                continue
            }
            val size = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + size > maxBytes) break
            out.append(char)
            bytes += size
            index++
        }
        return out.toString()
    }
}
