package io.ymusic.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import projectcommon.json.stringOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The envelope every manifest the app trusts arrives in — releases ([UpdateManifest]).
 *
 * The transport is never the trust boundary: whatever these manifests name ends up being executed,
 * so each is signed with an Ed25519 key whose public half is compiled into the app, and the
 * signature covers the `payload` STRING exactly as transmitted — re-serializing it (key order,
 * whitespace) can never change what was signed.
 * ```
 * { "payload": "{…}", "signature": "<base64 Ed25519>" }
 * ```
 */
object SignedPayload {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify [manifestJson] against [publicKeyBase64] and return its parsed payload.
     *
     * @throws UpdateVerificationException if the envelope is malformed, unsigned, or the signature
     * does not match — never a silent skip: an unverified manifest is treated as hostile.
     */
    fun open(manifestJson: String, publicKeyBase64: String): JsonObject {
        val root = try {
            json.parseToJsonElement(manifestJson).jsonObject
        } catch (e: IllegalArgumentException) {
            throw UpdateVerificationException("manifest is not a JSON object", e)
        }
        val payload = root.stringOrNull("payload")?.takeIf { it.isNotEmpty() }
            ?: throw UpdateVerificationException("manifest has no payload")
        val signature = root.stringOrNull("signature")?.takeIf { it.isNotEmpty() }
            ?: throw UpdateVerificationException("manifest is not signed")
        if (!verify(payload, signature, publicKeyBase64)) {
            throw UpdateVerificationException("manifest signature does not match the pinned key")
        }
        return try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: IllegalArgumentException) {
            throw UpdateVerificationException("payload is not a JSON object", e)
        }
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
