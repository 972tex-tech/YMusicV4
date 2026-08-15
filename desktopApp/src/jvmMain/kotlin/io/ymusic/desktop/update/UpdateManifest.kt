package io.ymusic.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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

class UpdateVerificationException(message: String) : Exception(message)

/**
 * Parses and AUTHENTICATES an update manifest.
 *
 * An updater downloads and runs code, so the transport is not the trust boundary: the manifest is
 * signed with an Ed25519 key whose public half is compiled into the app ([UPDATE_PUBLIC_KEY]), and
 * a manifest that does not verify against it is refused. Ed25519 comes from the JDK itself (17+),
 * so this costs no dependency.
 *
 * Wire shape — the signature covers the `payload` STRING exactly as transmitted, so re-serializing
 * it (key order, whitespace) can never change what was signed:
 * ```
 * { "payload": "{\"versionCode\":2,\"platforms\":{...}}", "signature": "<base64 Ed25519>" }
 * ```
 * The payload names, per platform, an `installer` (first install, and the fallback when a
 * file-level update cannot be applied) and the `files` making up the installed app.
 */
object UpdateManifest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify [manifestJson] against [publicKeyBase64] and return the release for [platformKey]
     * ("mac-x64", "mac-arm64", "windows-x64", "linux-x64"), or null when the manifest carries no
     * build for it.
     *
     * @throws UpdateVerificationException if the signature is absent, malformed, or wrong — never
     * a silent skip: an unverified manifest is treated as hostile, not as "no update".
     */
    fun parseVerified(manifestJson: String, publicKeyBase64: String, platformKey: String): Release? {
        val root = runCatching { json.parseToJsonElement(manifestJson).jsonObject }.getOrNull()
            ?: throw UpdateVerificationException("manifest is not a JSON object")
        val payload = root["payload"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw UpdateVerificationException("manifest has no payload")
        val signature = root["signature"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw UpdateVerificationException("manifest is not signed")
        if (!verify(payload, signature, publicKeyBase64)) {
            throw UpdateVerificationException("manifest signature does not match the pinned key")
        }

        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw UpdateVerificationException("payload is not a JSON object")
        val versionCode = body["versionCode"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull()
            ?: throw UpdateVerificationException("payload has no usable versionCode")
        val platform = (body["platforms"] as? JsonObject)?.get(platformKey)?.let { it as? JsonObject }
            ?: return null
        val installer = (platform["installer"] as? JsonObject)
            ?: throw UpdateVerificationException("platform entry has no installer")
        val files = (platform["files"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            val hash = o["sha256"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase() ?: return@mapNotNull null
            FileEntry(path, hash, o["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: -1L)
        }
        return Release(
            versionCode = versionCode,
            files = files,
            blobBaseUrl = platform["blobBaseUrl"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            versionName = body["versionName"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            url = installer["url"]?.jsonPrimitive?.contentOrNullSafe()
                ?: throw UpdateVerificationException("platform entry has no installer url"),
            sha256 = installer["sha256"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase()
                ?: throw UpdateVerificationException("platform entry has no installer sha256"),
            sizeBytes = installer["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: -1L,
            notes = body["notes"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        )
    }

    private fun verify(payload: String, signatureBase64: String, publicKeyBase64: String): Boolean =
        runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        }.getOrDefault(false)
}

/** kotlinx-serialization throws on a non-string primitive; this keeps parse errors uniform. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() }

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
