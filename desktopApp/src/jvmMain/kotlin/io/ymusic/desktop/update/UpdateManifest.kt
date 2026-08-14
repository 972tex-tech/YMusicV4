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
 * [url] points at a platform archive; [sha256] is checked against the downloaded bytes before
 * anything is unpacked. [notes] is shown to the user.
 */
data class Release(
    val version: Version,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/** A dotted release version — compared numerically, so 1.10.0 beats 1.9.0. */
data class Version(private val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** Lenient on purpose: a build suffix ("1.2.3+abc", "1.2.3-rc1") compares as 1.2.3 — the
         *  suffix identifies the build, it does not order releases. Null when unparseable. */
        fun parse(text: String): Version? {
            val core = text.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
            return if (parts.isEmpty()) null else Version(parts)
        }
    }
}

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
 * { "payload": "{\"version\":\"1.0.1\",...}", "signature": "<base64 Ed25519>" }
 * ```
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
        val version = body["version"]?.jsonPrimitive?.contentOrNullSafe()?.let(Version::parse)
            ?: throw UpdateVerificationException("payload has no usable version")
        val platform = (body["platforms"] as? JsonObject)?.get(platformKey)?.let { it as? JsonObject }
            ?: return null
        return Release(
            version = version,
            url = platform["url"]?.jsonPrimitive?.contentOrNullSafe()
                ?: throw UpdateVerificationException("platform entry has no url"),
            sha256 = platform["sha256"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase()
                ?: throw UpdateVerificationException("platform entry has no sha256"),
            sizeBytes = platform["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: -1L,
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
