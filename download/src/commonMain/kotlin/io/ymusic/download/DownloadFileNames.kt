package io.ymusic.download

/**
 * The one file-name policy for downloads, shared by the options dialog (live editing) and the pipeline
 * (publish). Forbidden are the characters any of Windows / macOS / Android SAF reject in a name — plus
 * control characters — so a name that works here works on every platform's storage.
 */
object DownloadFileNames {

    /** The characters a file name may never contain, as shown to the user. */
    const val FORBIDDEN_DISPLAY = "\\ / : * ? \" < > |"

    private const val MAX_LENGTH = 150

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

    /** The stem actually written: forbidden characters dropped, spaces collapsed, no leading/trailing
     *  spaces, dashes or dots (Windows rejects a trailing dot or space), capped at [MAX_LENGTH]. May be
     *  empty — the caller falls back to something non-empty. */
    fun finalize(input: String): String =
        stripForbidden(input)
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '-')
            .take(MAX_LENGTH)
            .trimEnd(' ', '.')
}
