package io.ymusic.desktop.setup

import io.ymusic.desktop.update.SignedPayload
import io.ymusic.desktop.update.UpdateVerificationException
import io.ymusic.desktop.update.asStringOrNull
import kotlinx.serialization.json.JsonObject

/** How the downloaded bytes get from the wire to an executable on disk. */
enum class ArchiveFormat {
    /** The download IS the binary. */
    RAW,

    /** A single gzip-compressed binary. */
    GZIP,

    /** A zip; the entry named by [ToolDownload.entry] is the binary. */
    ZIP,
    ;

    companion object {
        fun parse(raw: String?): ArchiveFormat = when (raw?.lowercase()) {
            null, "", "raw" -> RAW
            "gzip", "gz" -> GZIP
            "zip" -> ZIP
            else -> throw UpdateVerificationException("unknown archive format '$raw'")
        }
    }
}

/**
 * One tool build for one platform. [sha256] covers the DOWNLOADED bytes (the archive as published),
 * not the binary inside it, so the check happens before anything is unpacked — an archive is parsed
 * only once it is known to be ours.
 */
data class ToolDownload(
    val toolId: String,
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: ArchiveFormat,
    /** Which zip entry holds the binary; ignored by the other formats. */
    val entry: String?,
)

/**
 * The manifest naming the helper binaries the first run downloads (today: ffmpeg).
 *
 * It is signed with the SAME key as the release manifest and refused unless it verifies — these
 * downloads are executed, so a plain HTTPS fetch is not enough of a guarantee. It is a separate
 * document from the release manifest because tools change on their own schedule: a new app release
 * should not require re-publishing ffmpeg, or vice versa.
 *
 * ```
 * { "tools": { "ffmpeg": { "version": "7.1", "platforms": {
 *     "mac-x64": { "url": "…", "sha256": "…", "size": 42, "format": "gzip" } } } } }
 * ```
 */
object ToolsManifest {

    const val FFMPEG = "ffmpeg"

    /**
     * Verify [manifestJson] against [publicKeyBase64] and return the [toolId] build for
     * [platformKey], or null when the manifest carries none for this platform.
     *
     * @throws UpdateVerificationException if the manifest is unsigned, tampered with, or an entry
     * that exists is unusable (a missing url or hash is a publishing bug, not "no build here").
     */
    fun parseVerified(
        manifestJson: String,
        publicKeyBase64: String,
        platformKey: String,
        toolId: String = FFMPEG,
    ): ToolDownload? {
        val body = SignedPayload.open(manifestJson, publicKeyBase64)
        val tool = (body["tools"] as? JsonObject)?.get(toolId) as? JsonObject ?: return null
        val platform = (tool["platforms"] as? JsonObject)?.get(platformKey) as? JsonObject ?: return null
        return ToolDownload(
            toolId = toolId,
            version = tool["version"]?.asStringOrNull()
                ?: throw UpdateVerificationException("$toolId entry has no version"),
            url = platform["url"]?.asStringOrNull()
                ?: throw UpdateVerificationException("$toolId/$platformKey has no url"),
            sha256 = platform["sha256"]?.asStringOrNull()?.lowercase()
                ?: throw UpdateVerificationException("$toolId/$platformKey has no sha256"),
            sizeBytes = platform["size"]?.asStringOrNull()?.toLongOrNull() ?: -1L,
            format = ArchiveFormat.parse(platform["format"]?.asStringOrNull()),
            entry = platform["entry"]?.asStringOrNull(),
        )
    }
}
