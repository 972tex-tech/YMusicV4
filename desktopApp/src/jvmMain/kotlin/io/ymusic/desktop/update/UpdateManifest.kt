package io.ymusic.desktop.update

import kotlinx.serialization.json.JsonObject
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/**
 * One release, as published in the update manifest.
 *
 * [versionCode] is the only thing ordering compares — a monotonic release counter, as with
 * Android's versionCode and the engine-js backend revision. [versionName] is for display and
 * carries no ordering meaning. [url] points at a platform archive; [sha256] is checked against the
 * downloaded bytes before anything is unpacked.
 */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    /** Every file of the installed app, addressed by content hash. */
    val files: List<FileEntry>,
    /** Where the content-addressed blobs live; a file's blob is `$blobBaseUrl/<sha256>`. */
    val blobBaseUrl: String,
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    fun blobUrl(entry: FileEntry): String = blobBaseUrl.trimEnd('/') + "/" + entry.sha256
}

/** One file of a release: [path] is relative to the app root, [sha256] identifies its content. */
data class FileEntry(val path: String, val sha256: String, val size: Long)

class UpdateVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses and AUTHENTICATES an update manifest.
 *
 * An updater downloads and runs code, so the transport is not the trust boundary — see
 * [SignedPayload] for the envelope and why the signature covers the payload string verbatim. The
 * payload names, per platform, an `installer` (first install, and the fallback when a file-level
 * update cannot be applied) and the `files` making up the installed app:
 * ```
 * { "payload": "{\"versionCode\":2,\"platforms\":{...}}", "signature": "<base64 Ed25519>" }
 * ```
 */
object UpdateManifest {

    /**
     * Verify [manifestJson] against [publicKeyBase64] and return the release for [platformKey]
     * ("mac-x64", "mac-arm64", "windows-x64", "linux-x64"), or null when the manifest carries no
     * build for it.
     *
     * @throws UpdateVerificationException if the signature is absent, malformed, or wrong — never
     * a silent skip: an unverified manifest is treated as hostile, not as "no update".
     */
    fun parseVerified(manifestJson: String, publicKeyBase64: String, platformKey: String): Release? {
        val body = SignedPayload.open(manifestJson, publicKeyBase64)
        val versionCode = body.intOrNull("versionCode")
            ?: throw UpdateVerificationException("payload has no usable versionCode")
        val platform = body.objectOrNull("platforms")?.objectOrNull(platformKey)
            ?: return null
        val installer = platform.objectOrNull("installer")
            ?: throw UpdateVerificationException("platform entry has no installer")
        val files = platform.objects("files").mapNotNull { o ->
            val path = o.nonEmptyString("path") ?: return@mapNotNull null
            val hash = o.nonEmptyString("sha256")?.lowercase() ?: return@mapNotNull null
            FileEntry(path, hash, o.longOrNull("size") ?: -1L)
        }
        return Release(
            versionCode = versionCode,
            files = files,
            blobBaseUrl = platform.stringOrNull("blobBaseUrl").orEmpty(),
            versionName = body.stringOrNull("versionName").orEmpty(),
            url = installer.nonEmptyString("url")
                ?: throw UpdateVerificationException("platform entry has no installer url"),
            sha256 = installer.nonEmptyString("sha256")?.lowercase()
                ?: throw UpdateVerificationException("platform entry has no installer sha256"),
            sizeBytes = installer.longOrNull("size") ?: -1L,
            notes = body.stringOrNull("notes").orEmpty(),
        )
    }
}

/** The host triple the manifest keys releases by. */
fun currentPlatformKey(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    return when {
        os.contains("mac") || os.contains("darwin") -> "mac-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}

private fun JsonObject.nonEmptyString(key: String): String? = stringOrNull(key)?.takeIf { it.isNotEmpty() }
