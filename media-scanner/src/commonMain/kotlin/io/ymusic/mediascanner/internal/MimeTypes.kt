package io.ymusic.mediascanner.internal

// Common audio/video containers YMusic cares about, mapped from file extension. Used by filesystem
// backends (which have no mime database) and by the scanner's media filter.
internal val AUDIO_EXTENSIONS: Map<String, String> = mapOf(
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "m4b" to "audio/mp4", "aac" to "audio/aac",
    "flac" to "audio/flac", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus",
    "wav" to "audio/wav", "wv" to "audio/x-wavpack", "aa" to "audio/audible", "aax" to "audio/vnd.audible.aax",
    "3gp" to "audio/3gpp", "webm" to "audio/webm", "mogg" to "audio/ogg", "raw" to "audio/basic",
)

internal val VIDEO_EXTENSIONS: Map<String, String> = mapOf(
    "mp4" to "video/mp4", "m4v" to "video/mp4", "m4p" to "video/mp4", "mkv" to "video/x-matroska",
    "webm" to "video/webm", "mov" to "video/quicktime", "qt" to "video/quicktime", "avi" to "video/x-msvideo",
    "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
    "mpe" to "video/mpeg", "mp2" to "video/mpeg", "mpv" to "video/mpeg", "m2v" to "video/mpeg",
    "ogv" to "video/ogg", "3g2" to "video/3gpp2", "3gp" to "video/3gpp",
)

internal fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot in 0 until name.length - 1) name.substring(dot + 1).lowercase() else ""
}

/** A best-effort mime type from a filename; "application/octet-stream" when unknown. */
internal fun guessMimeType(name: String): String {
    val ext = extensionOf(name)
    return AUDIO_EXTENSIONS[ext] ?: VIDEO_EXTENSIONS[ext] ?: "application/octet-stream"
}

/** True when a document looks like a media file the scanner should index. */
internal fun isMediaFile(name: String, mimeType: String): Boolean {
    if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) return true
    val ext = extensionOf(name)
    return ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
}
